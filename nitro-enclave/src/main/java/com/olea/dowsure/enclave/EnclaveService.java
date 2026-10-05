package com.olea.dowsure.enclave;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

public final class EnclaveService {
    private static final String SOURCE = "mock-api";
    private static final String ENDPOINT = "GET_ORDERS";
    private static final String CANONICALIZATION = "RFC8785-PoC";

    private final ObjectMapper mapper;
    private final AttestationProvider attestationProvider;

    public EnclaveService(ObjectMapper mapper, AttestationProvider attestationProvider) {
        this.mapper = mapper;
        this.attestationProvider = attestationProvider;
    }

    public Map<String, Object> acquire(Map<String, Object> request) {
        require(request, "requestId", "nonce", "policyVersion", "evidenceId", "eifDigest", "rawPayload", "rawResponseB64", "tlsProof");
        if (!SOURCE.equals(request.get("source")) || !ENDPOINT.equals(request.get("endpoint"))) throw new IllegalArgumentException("SOURCE_SCOPE_INVALID");

        Map<String, Object> rawPayload = map(request.get("rawPayload"), "rawPayload");
        Map<String, Object> tlsProof = map(request.get("tlsProof"), "tlsProof");
        if (!"tlsnotary".equals(tlsProof.get("proofType"))) throw new IllegalArgumentException("TLS_PROOF_INVALID");
        Map<String, Object> proofMaterial = new TreeMap<>(tlsProof);
        proofMaterial.remove("proofHash");
        if (!sha256(canonical(proofMaterial)).equals(tlsProof.get("proofHash"))) throw new IllegalArgumentException("TLS_PROOF_INVALID");

        byte[] rawResponseBytes = Base64.getDecoder().decode((String) request.get("rawResponseB64"));
        String rawHash = sha256Bytes(rawResponseBytes);
        if (!rawHash.equals(tlsProof.get("responseHash"))) throw new IllegalArgumentException("TLS_PROOF_HASH_MISMATCH");
        Map<String, Object> transformed = transform(rawPayload);
        String transformedHash = sha256(canonical(transformed));

        KeyPair keyPair = generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("requestId", request.get("requestId"));
        binding.put("nonce", request.get("nonce"));
        binding.put("policyVersion", request.get("policyVersion"));
        binding.put("rawHash", rawHash);
        binding.put("transformedHash", transformedHash);
        binding.put("publicKey", publicKey);
        binding.put("tlsProofHash", tlsProof.get("proofHash"));

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("requestId", request.get("requestId"));
        manifest.put("evidenceId", request.get("evidenceId"));
        manifest.put("source", SOURCE);
        manifest.put("endpoint", ENDPOINT);
        manifest.put("nonce", request.get("nonce"));
        manifest.put("policyVersion", request.get("policyVersion"));
        manifest.put("rawSourceHash", rawHash);
        manifest.put("transformedHash", transformedHash);
        manifest.put("tlsProofType", "tlsnotary");
        manifest.put("tlsProofHash", tlsProof.get("proofHash"));
        manifest.put("canonicalizationVersion", CANONICALIZATION);
        manifest.put("attestedPublicKeyBase64", publicKey);

        String manifestDigest = sha256(canonical(manifest));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("rawPayload", rawPayload);
        evidence.put("rawPayloadDigest", rawHash);
        evidence.put("transformedPayload", transformed);
        evidence.put("transformedPayloadDigest", transformedHash);
        evidence.put("canonicalizationVersion", CANONICALIZATION);
        evidence.put("tlsProofType", "tlsnotary");
        evidence.put("tlsProofHash", tlsProof.get("proofHash"));
        evidence.put("tlsProofResponseHash", rawHash);
        evidence.put("attestedPublicKeyBase64", publicKey);
        evidence.put("manifestDigest", manifestDigest);
        evidence.put("encryptedEvidenceReference", "vsock://opaque/" + request.get("evidenceId"));
        evidence.put("evidenceId", request.get("evidenceId"));
        evidence.put("eifDigest", request.get("eifDigest"));
        evidence.put("enclaveSignature", sign(canonical(manifest), keyPair));
        evidence.put("attestationDocument", Base64.getUrlEncoder().withoutPadding().encodeToString(attestationProvider.generateAttestation(canonical(binding).getBytes(StandardCharsets.UTF_8), keyPair.getPublic().getEncoded())));
        return evidence;
    }

    private Map<String, Object> transform(Map<String, Object> rawPayload) {
        Object payload = rawPayload.get("payload");
        Map<String, Object> payloadMap = payload instanceof Map<?, ?> value ? castMap(value) : Map.of();
        return Map.of("orders", payloadMap.getOrDefault("Orders", java.util.List.of()));
    }

    private KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (Exception error) {
            throw new IllegalStateException("ENCLAVE_KEY_GENERATION_FAILED", error);
        }
    }

    private String sign(String value, KeyPair keyPair) {
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(keyPair.getPrivate());
            signature.update(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception error) {
            throw new IllegalStateException("ENCLAVE_SIGNATURE_FAILED", error);
        }
    }

    String canonical(Object value) {
        try {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> sorted = new TreeMap<>();
                map.forEach((key, item) -> sorted.put(String.valueOf(key), item));
                return mapper.writeValueAsString(sorted);
            }
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("NON_CANONICAL_VALUE", error);
        }
    }

    String sha256(String value) {
        return sha256Bytes(value.getBytes(StandardCharsets.UTF_8));
    }

    String sha256Bytes(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static void require(Map<String, Object> request, String... fields) {
        for (String field : fields) if (!request.containsKey(field) || request.get(field) == null) throw new IllegalArgumentException("REQUEST_FIELD_MISSING:" + field);
    }

    private static Map<String, Object> map(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("REQUEST_FIELD_INVALID:" + field);
        return castMap(map);
    }

    private static Map<String, Object> castMap(Map<?, ?> value) {
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
}
