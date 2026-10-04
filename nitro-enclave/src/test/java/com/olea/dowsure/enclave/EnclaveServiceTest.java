package com.olea.dowsure.enclave;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnclaveServiceTest {
    private final EnclaveService service = new EnclaveService(new ObjectMapper(), (userData, publicKey) -> "attestation".getBytes());

    @Test
    void bindsTlsProofToRawResponseAndProducesManifest() {
        Map<String, Object> raw = Map.of("payload", Map.of("Orders", List.of(Map.of("orderId", "123"))));
        Map<String, Object> proofMaterial = Map.of("proofType", "tlsnotary", "responseHash", "pending");
        String rawHash = service.sha256(service.canonical(raw));
        proofMaterial = Map.of("proofType", "tlsnotary", "responseHash", rawHash, "transcript", "fixture");
        Map<String, Object> proof = Map.of("proofType", "tlsnotary", "responseHash", rawHash, "transcript", "fixture", "proofHash", service.sha256(service.canonical(proofMaterial)));
        Map<String, Object> request = Map.of("requestId", "r1", "nonce", "n1", "policyVersion", "v1", "evidenceId", "e1", "eifDigest", "eif", "source", "mock-api", "endpoint", "GET_ORDERS", "rawPayload", raw, "tlsProof", proof);

        Map<String, Object> evidence = service.acquire(request);

        assertEquals(rawHash, evidence.get("rawPayloadDigest"));
        assertEquals("tlsnotary", evidence.get("tlsProofType"));
        assertEquals("attestation", new String(Base64.getUrlDecoder().decode((String) evidence.get("attestationDocument"))));
    }

    @Test
    void rejectsTlsHashMismatch() {
        Map<String, Object> raw = Map.of("payload", Map.of("Orders", List.of()));
        Map<String, Object> proof = Map.of("proofType", "tlsnotary", "responseHash", "wrong", "transcript", "fixture", "proofHash", service.sha256(service.canonical(Map.of("proofType", "tlsnotary", "responseHash", "wrong", "transcript", "fixture"))));
        Map<String, Object> request = Map.of("requestId", "r1", "nonce", "n1", "policyVersion", "v1", "evidenceId", "e1", "eifDigest", "eif", "source", "mock-api", "endpoint", "GET_ORDERS", "rawPayload", raw, "tlsProof", proof);
        assertThrows(IllegalArgumentException.class, () -> service.acquire(request));
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
        String rawHash = capturing.sha256(capturing.canonical(raw));
        Map<String, Object> proofMaterial = Map.of("proofType", "tlsnotary", "responseHash", rawHash, "transcript", "fixture");
        String proofHash = capturing.sha256(capturing.canonical(proofMaterial));
        Map<String, Object> proof = Map.of("proofType", "tlsnotary", "responseHash", rawHash, "transcript", "fixture", "proofHash", proofHash);
        Map<String, Object> request = Map.of("requestId", "r1", "nonce", "n1", "policyVersion", "v1", "evidenceId", "e1", "eifDigest", "eif", "source", "mock-api", "endpoint", "GET_ORDERS", "rawPayload", raw, "tlsProof", proof);

        capturing.acquire(request);

        Map<String, Object> userData = mapper.readValue(new String(capturedUserData.get(), StandardCharsets.UTF_8), new TypeReference<>() {});
        assertEquals("n1", userData.get("nonce"));
        assertEquals(proofHash, userData.get("tlsProofHash"));
    }

}
