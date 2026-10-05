package com.olea.dowsure.coordinator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the coordinator orchestration with mocked network/vsock boundaries:
 * challenge body, enclave request field set (incl. tlsProof pass-through),
 * envelope key order/values, result shape, signature over the canonical
 * envelope, and fail-closed propagation of an enclave {@code ok:false}.
 */
class CoordinatorTest {

    private static final String OLEA_URL = "https://olea.example";

    /** Records the URL and body it was posted, returns a fixed challenge. */
    private static final class RecordingOleaClient implements OleaClient {
        String url;
        Map<String, Object> body;
        private final Map<String, Object> challenge;

        RecordingOleaClient(Map<String, Object> challenge) {
            this.challenge = challenge;
        }

        @Override
        public Map<String, Object> post(String url, Map<String, Object> body) {
            this.url = url;
            this.body = body;
            return challenge;
        }
    }

    /** Records the enclave request, returns a fixed evidence map. */
    private static final class RecordingEnclaveClient implements EnclaveClient {
        int cid;
        int port;
        Map<String, Object> request;
        private final Map<String, Object> evidence;

        RecordingEnclaveClient(Map<String, Object> evidence) {
            this.evidence = evidence;
        }

        @Override
        public Map<String, Object> invoke(int cid, int port, Map<String, Object> request) {
            this.cid = cid;
            this.port = port;
            this.request = request;
            return new LinkedHashMap<>(evidence);
        }
    }

    private static Map<String, Object> sampleChallenge() {
        Map<String, Object> challenge = new LinkedHashMap<>();
        challenge.put("nonce", "nonce-xyz");
        challenge.put("policyVersion", "v1.0");
        return challenge;
    }

    private static Map<String, Object> sampleEvidence() {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("manifestDigest", "digest-123");
        evidence.put("encryptedEvidenceReference", "vsock://opaque/e1");
        return evidence;
    }

    /** A test EC key written to a PEM file, keeping the public key for verification. */
    private record TestKey(Path file, java.security.PublicKey publicKey) {
    }

    private static TestKey writeTestKey(Path tempDir) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = generator.generateKeyPair();
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(keyPair.getPrivate().getEncoded());
        Path keyFile = tempDir.resolve("key-" + System.nanoTime() + ".pem");
        Files.writeString(keyFile, "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n");
        return new TestKey(keyFile, keyPair.getPublic());
    }

    @Test
    void challengeBodyHasExactShape() {
        Map<String, Object> body = Coordinator.challengeBody("req-1", "getOrderMetrics");
        assertEquals("req-1", body.get("requestId"));
        assertEquals("getOrderMetrics", body.get("sourceId"));
        assertEquals("v1.0", body.get("policyVersion"));
        assertEquals(3, body.size());
    }

    @Test
    void enclaveRequestCarriesSourceIdAndRequestBodyAndDropsLegacyFields() {
        Map<String, Object> requestBody = Map.of("companyName", "Acme");
        Map<String, Object> request = Coordinator.enclaveRequest(
                "req-1", sampleChallenge(), "gutuPanoramaChecks", requestBody, "ev-1", "eif-digest");

        assertEquals("req-1", request.get("requestId"));
        assertEquals("nonce-xyz", request.get("nonce"));
        assertEquals("v1.0", request.get("policyVersion"));
        assertEquals("ev-1", request.get("evidenceId"));
        assertEquals("eif-digest", request.get("eifDigest"));
        assertEquals("gutuPanoramaChecks", request.get("sourceId"));
        // requestBody is carried through for the POST case.
        assertEquals(requestBody, request.get("requestBody"));
        // Legacy response/proof fields are gone — the enclave fetches R itself.
        assertTrue(!request.containsKey("rawPayload"), "rawPayload must be dropped");
        assertTrue(!request.containsKey("rawResponseB64"), "rawResponseB64 must be dropped");
        assertTrue(!request.containsKey("tlsProof"), "tlsProof must be dropped");
    }

    @Test
    void enclaveRequestOmitsRequestBodyWhenNull() {
        Map<String, Object> request = Coordinator.enclaveRequest(
                "req-1", sampleChallenge(), "getOrderMetrics", null, "ev-1", "eif-digest");

        assertEquals("getOrderMetrics", request.get("sourceId"));
        assertTrue(!request.containsKey("requestBody"), "requestBody must be omitted when null");
    }

    @Test
    void envelopeHasExpectedKeyOrderAndValues() {
        Map<String, Object> envelope = Coordinator.submissionEnvelope(
                "req-1", sampleChallenge(), "ev-1", sampleEvidence(), "2026-01-01T00:00:00Z");

        assertEquals(List.of("requestId", "nonce", "policyVersion", "evidenceId",
                        "manifestDigest", "encryptedEvidenceReference", "submittedAt"),
                new ArrayList<>(envelope.keySet()));
        assertEquals("req-1", envelope.get("requestId"));
        assertEquals("nonce-xyz", envelope.get("nonce"));
        assertEquals("v1.0", envelope.get("policyVersion"));
        assertEquals("ev-1", envelope.get("evidenceId"));
        assertEquals("digest-123", envelope.get("manifestDigest"));
        assertEquals("vsock://opaque/e1", envelope.get("encryptedEvidenceReference"));
        assertEquals("2026-01-01T00:00:00Z", envelope.get("submittedAt"));
    }

    @Test
    void runWiresChallengeEnclaveAndSignsEnvelope(@TempDir Path tempDir) throws Exception {
        RecordingOleaClient olea = new RecordingOleaClient(sampleChallenge());
        RecordingEnclaveClient enclave = new RecordingEnclaveClient(sampleEvidence());
        TestKey testKey = writeTestKey(tempDir);
        Path keyFile = testKey.file();
        Coordinator coordinator = new Coordinator(olea, enclave, new Signer());
        String sourceId = "getOrderMetrics";

        Map<String, Object> result = coordinator.run(
                OLEA_URL, 16, 5005, sourceId, null, keyFile, "eif-digest");

        // Olea challenge call targeted the right URL and sent the exact body.
        assertEquals(OLEA_URL + "/v1/challenges", olea.url);
        assertEquals(Coordinator.challengeBody((String) result.get("requestId"), sourceId), olea.body);

        // Enclave invoked at the given cid/port driven by sourceId.
        assertEquals(16, enclave.cid);
        assertEquals(5005, enclave.port);
        assertEquals(sourceId, enclave.request.get("sourceId"));

        // Result shape consumed by scripts/phase1-evidence-report.js.
        assertEquals(List.of("requestId", "evidenceId", "challenge", "evidence"),
                new ArrayList<>(result.keySet()));

        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) result.get("evidence");
        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = (Map<String, Object>) evidence.get("submissionEnvelope");
        assertEquals(result.get("requestId"), envelope.get("requestId"));
        assertEquals("digest-123", envelope.get("manifestDigest"));

        // submissionSignature verifies over the canonical envelope with the test key.
        String signatureB64 = (String) evidence.get("submissionSignature");
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(testKey.publicKey());
        verifier.update(Canonicalizer.canonicalize(envelope).getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getDecoder().decode(signatureB64)),
                "submission signature must verify over the canonical envelope");
    }

    @Test
    void runFailsClosedWhenEnclaveRejects(@TempDir Path tempDir) throws Exception {
        OleaClient olea = (url, body) -> sampleChallenge();
        EnclaveClient enclave = (cid, port, request) -> {
            throw new IllegalStateException("POLICY_SCOPE_INVALID");
        };
        Coordinator coordinator = new Coordinator(olea, enclave, new Signer());

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> coordinator.run(
                OLEA_URL, 16, 5005, "getOrderMetrics", null, writeTestKey(tempDir).file(), "eif-digest"));
        assertEquals("POLICY_SCOPE_INVALID", error.getMessage());
    }
}
