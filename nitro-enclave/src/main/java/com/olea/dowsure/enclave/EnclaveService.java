package com.olea.dowsure.enclave;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
    private static final String CANONICALIZATION = "RFC8785-PoC";

    private final ObjectMapper mapper;
    private final AttestationProvider attestationProvider;
    private final SourceTlsClient sourceTlsClient;
    private final CredentialProvider credentialProvider;
    private final SourceRegistry sourceRegistry;

    public EnclaveService(ObjectMapper mapper,
                          AttestationProvider attestationProvider,
                          SourceTlsClient sourceTlsClient,
                          CredentialProvider credentialProvider,
                          SourceRegistry sourceRegistry) {
        this.mapper = mapper;
        this.attestationProvider = attestationProvider;
        this.sourceTlsClient = sourceTlsClient;
        this.credentialProvider = credentialProvider;
        this.sourceRegistry = sourceRegistry;
    }

    public Map<String, Object> acquire(Map<String, Object> request) {
        require(request, "requestId", "nonce", "policyVersion", "evidenceId", "eifDigest", "sourceId");

        String sourceId = (String) request.get("sourceId");
        SourceEntry entry = sourceRegistry.resolve(sourceId);
        Map<String, String> headers = credentialProvider.headersFor(entry, request);
        byte[] rawResponseBytes = sourceTlsClient.fetch(entry, request.get("requestBody"), headers);
        String rawResponseB64 = Base64.getEncoder().encodeToString(rawResponseBytes);
        String rawHash = sha256Bytes(rawResponseBytes);
        Map<String, Object> rawPayload = parseBody(rawResponseBytes);

        // PURE PASS-THROUGH transform: the raw payload is echoed as-is, no logic.
        Map<String, Object> transformed = rawPayload;
        String transformedHash = sha256(canonical(transformed));

        KeyPair keyPair = generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("requestId", request.get("requestId"));
        binding.put("nonce", request.get("nonce"));
        binding.put("policyVersion", request.get("policyVersion"));
        binding.put("sourceId", sourceId);
        binding.put("rawHash", rawHash);
        binding.put("transformedHash", transformedHash);
        binding.put("publicKey", publicKey);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("requestId", request.get("requestId"));
        manifest.put("evidenceId", request.get("evidenceId"));
        manifest.put("sourceId", sourceId);
        manifest.put("nonce", request.get("nonce"));
        manifest.put("policyVersion", request.get("policyVersion"));
        manifest.put("rawSourceHash", rawHash);
        manifest.put("transformedHash", transformedHash);
        manifest.put("canonicalizationVersion", CANONICALIZATION);
        manifest.put("attestedPublicKeyBase64", publicKey);

        String manifestDigest = sha256(canonical(manifest));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("rawPayload", rawPayload);
        evidence.put("rawPayloadDigest", rawHash);
        evidence.put("rawResponseB64", rawResponseB64);
        evidence.put("transformedPayload", transformed);
        evidence.put("transformedPayloadDigest", transformedHash);
        evidence.put("canonicalizationVersion", CANONICALIZATION);
        evidence.put("attestedPublicKeyBase64", publicKey);
        evidence.put("manifestDigest", manifestDigest);
        evidence.put("encryptedEvidenceReference", "vsock://opaque/" + request.get("evidenceId"));
        evidence.put("evidenceId", request.get("evidenceId"));
        evidence.put("eifDigest", request.get("eifDigest"));
        evidence.put("enclaveSignature", sign(canonical(manifest), keyPair));
        evidence.put("attestationDocument", Base64.getUrlEncoder().withoutPadding().encodeToString(attestationProvider.generateAttestation(canonical(binding).getBytes(StandardCharsets.UTF_8), keyPair.getPublic().getEncoded())));
        return evidence;
    }

    /**
     * Parse the JSON body out of the full HTTP response bytes R (status line +
     * headers + body). Used only to carry the parsed payload for pass-through; the
     * binding/hash is always over the exact wire bytes R.
     */
    private Map<String, Object> parseBody(byte[] response) {
        try {
            String text = new String(response, StandardCharsets.UTF_8);
            int split = text.indexOf("\r\n\r\n");
            String body = split >= 0 ? text.substring(split + 4) : text;
            return mapper.readValue(body, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception error) {
            throw new IllegalArgumentException("SOURCE_RESPONSE_INVALID", error);
        }
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
}
