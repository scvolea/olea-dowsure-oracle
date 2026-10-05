package com.olea.dowsure.enclave;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnclaveServiceTest {
    private final EnclaveService service = new EnclaveService(new ObjectMapper(), (userData, publicKey) -> "attestation".getBytes());

    // The enclave now hashes the DECODED rawResponseB64 wire bytes. Each test picks raw
    // response bytes (UTF-8 of a small JSON string), sets rawResponseB64 = base64(bytes),
    // and derives responseHash = sha256(bytes) via service.sha256Bytes so it equals the
    // enclave's rawHash. rawPayload (the parsed object) is kept for the transform.
    private static Map<String, Object> request(Map<String, Object> raw, String rawResponseB64, Map<String, Object> proof) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("requestId", "r1");
        request.put("nonce", "n1");
        request.put("policyVersion", "v1");
        request.put("evidenceId", "e1");
        request.put("eifDigest", "eif");
        request.put("source", "mock-api");
        request.put("endpoint", "GET_ORDERS");
        request.put("rawPayload", raw);
        request.put("rawResponseB64", rawResponseB64);
        request.put("tlsProof", proof);
        return request;
    }

    @Test
    void bindsTlsProofToRawResponseAndProducesManifest() {
        Map<String, Object> raw = Map.of("payload", Map.of("Orders", List.of(Map.of("orderId", "123"))));
        byte[] rawBytes = "{\"payload\":{\"Orders\":[{\"orderId\":\"123\"}]}}".getBytes(StandardCharsets.UTF_8);
        String rawResponseB64 = Base64.getEncoder().encodeToString(rawBytes);
        String rawHash = service.sha256Bytes(rawBytes);
        Map<String, Object> proofMaterial = Map.of("proofType", "tlsnotary", "responseHash", rawHash, "transcript", "fixture");
        Map<String, Object> proof = Map.of("proofType", "tlsnotary", "responseHash", rawHash, "transcript", "fixture", "proofHash", service.sha256(service.canonical(proofMaterial)));

        Map<String, Object> evidence = service.acquire(request(raw, rawResponseB64, proof));

        assertEquals(rawHash, evidence.get("rawPayloadDigest"));
        assertEquals(rawHash, evidence.get("tlsProofResponseHash"));
        assertEquals("tlsnotary", evidence.get("tlsProofType"));
        assertEquals("attestation", new String(Base64.getUrlDecoder().decode((String) evidence.get("attestationDocument"))));
    }

    @Test
    void rejectsTlsHashMismatch() {
        Map<String, Object> raw = Map.of("payload", Map.of("Orders", List.of()));
        byte[] rawBytes = "{\"payload\":{\"Orders\":[]}}".getBytes(StandardCharsets.UTF_8);
        String rawResponseB64 = Base64.getEncoder().encodeToString(rawBytes);
        // responseHash is deliberately "wrong" (does not match sha256(rawBytes)), so the
        // code reaches TLS_PROOF_HASH_MISMATCH after require() passes with valid base64.
        Map<String, Object> proof = Map.of("proofType", "tlsnotary", "responseHash", "wrong", "transcript", "fixture", "proofHash", service.sha256(service.canonical(Map.of("proofType", "tlsnotary", "responseHash", "wrong", "transcript", "fixture"))));
        assertThrows(IllegalArgumentException.class, () -> service.acquire(request(raw, rawResponseB64, proof)));
    }

    @Test
    void bindsNonceAndTlsProofHashIntoAttestationUserData() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<byte[]> capturedUserData = new AtomicReference<>();
        EnclaveService capturing = new EnclaveService(mapper, (userData, publicKey) -> {
            capturedUserData.set(userData);
            return "attestation".getBytes();
        });

        Map<String, Object> raw = Map.of("payload", Map.of("Orders", List.of(Map.of("orderId", "123"))));
        byte[] rawBytes = "{\"payload\":{\"Orders\":[{\"orderId\":\"123\"}]}}".getBytes(StandardCharsets.UTF_8);
        String rawResponseB64 = Base64.getEncoder().encodeToString(rawBytes);
        String rawHash = capturing.sha256Bytes(rawBytes);
        Map<String, Object> proofMaterial = Map.of("proofType", "tlsnotary", "responseHash", rawHash, "transcript", "fixture");
        String proofHash = capturing.sha256(capturing.canonical(proofMaterial));
        Map<String, Object> proof = Map.of("proofType", "tlsnotary", "responseHash", rawHash, "transcript", "fixture", "proofHash", proofHash);

        capturing.acquire(request(raw, rawResponseB64, proof));

        Map<String, Object> userData = mapper.readValue(new String(capturedUserData.get(), StandardCharsets.UTF_8), new TypeReference<>() {});
        assertEquals("n1", userData.get("nonce"));
        assertEquals(proofHash, userData.get("tlsProofHash"));
    }

}
