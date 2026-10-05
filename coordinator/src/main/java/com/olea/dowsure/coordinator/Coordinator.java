package com.olea.dowsure.coordinator;

import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates the Dowsure evidence flow, functionally equivalent to the Python
 * {@code coordinator.py main()}: request a challenge from Olea, dispatch to the
 * enclave over vsock, build and sign the submission envelope, and return the
 * result map {@code {requestId, evidenceId, challenge, evidence}}.
 *
 * <p>All cloud/vendor access is behind the injected {@link OleaClient} and
 * {@link EnclaveClient} adapters. The ephemeral private key is read only inside
 * {@link Signer} during signing and is never logged or persisted. The
 * {@code tlsProof} value is passed through to the enclave untouched — the
 * enclave, not the coordinator, validates it (TLSNotary proof is a separate
 * upcoming task; the placeholder contract field stays wire-compatible).
 */
public final class Coordinator {
    private static final String SOURCE = "mock-api";
    private static final String ENDPOINT = "GET_ORDERS";

    private final OleaClient oleaClient;
    private final EnclaveClient enclaveClient;
    private final Signer signer;

    public Coordinator(OleaClient oleaClient, EnclaveClient enclaveClient, Signer signer) {
        this.oleaClient = oleaClient;
        this.enclaveClient = enclaveClient;
        this.signer = signer;
    }

    /**
     * Builds the challenge request body sent to {@code <olea-url>/v1/challenges}.
     * Exposed (package-private) so tests can assert the exact body shape.
     */
    static Map<String, Object> challengeBody(String requestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("source", SOURCE);
        body.put("endpoint", ENDPOINT);
        body.put("operation", ENDPOINT);
        body.put("policyVersion", "v1.0");
        return body;
    }

    /**
     * Builds the enclave request map, preserving the exact field set and the
     * {@code tlsProof} opaque pass-through from the Python coordinator.
     */
    static Map<String, Object> enclaveRequest(String requestId, Map<String, Object> challenge, Object rawPayload,
                                              String rawResponseB64, Object tlsProof, String evidenceId, String eifDigest) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("requestId", requestId);
        request.put("source", SOURCE);
        request.put("endpoint", ENDPOINT);
        request.put("nonce", challenge.get("nonce"));
        request.put("policyVersion", challenge.get("policyVersion"));
        request.put("rawPayload", rawPayload);
        request.put("rawResponseB64", rawResponseB64);
        request.put("tlsProof", tlsProof);
        request.put("evidenceId", evidenceId);
        request.put("eifDigest", eifDigest);
        return request;
    }

    /**
     * Builds the submission envelope in the same key insertion order as the
     * Python coordinator (the signature is over the canonical, key-sorted form,
     * so order is cosmetic for the signature but preserved for fidelity).
     */
    static Map<String, Object> submissionEnvelope(String requestId, Map<String, Object> challenge,
                                                  String evidenceId, Map<String, Object> evidence, String submittedAt) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("requestId", requestId);
        envelope.put("nonce", challenge.get("nonce"));
        envelope.put("policyVersion", challenge.get("policyVersion"));
        envelope.put("evidenceId", evidenceId);
        envelope.put("manifestDigest", evidence.get("manifestDigest"));
        envelope.put("encryptedEvidenceReference", evidence.get("encryptedEvidenceReference"));
        envelope.put("submittedAt", submittedAt);
        return envelope;
    }

    /**
     * Runs the full flow. {@code requestId}/{@code evidenceId} are generated as
     * random UUIDs (as in Python). Returns the result map that
     * {@code scripts/phase1-evidence-report.js} consumes verbatim.
     */
    public Map<String, Object> run(String oleaUrl, int enclaveCid, int enclavePort,
                                    Object rawPayload, String rawResponseB64, Object tlsProof, Path dowsurePrivateKeyFile, String eifDigest) {
        String requestId = UUID.randomUUID().toString();
        String evidenceId = UUID.randomUUID().toString();

        Map<String, Object> challenge = oleaClient.post(oleaUrl + "/v1/challenges", challengeBody(requestId));

        Map<String, Object> evidence = enclaveClient.invoke(enclaveCid, enclavePort,
                enclaveRequest(requestId, challenge, rawPayload, rawResponseB64, tlsProof, evidenceId, eifDigest));

        String submittedAt = isoUtcNow();
        Map<String, Object> envelope = submissionEnvelope(requestId, challenge, evidenceId, evidence, submittedAt);
        evidence.put("submissionEnvelope", envelope);
        evidence.put("submissionSignature", signer.sign(dowsurePrivateKeyFile, Canonicalizer.canonicalize(envelope)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", requestId);
        result.put("evidenceId", evidenceId);
        result.put("challenge", challenge);
        result.put("evidence", evidence);
        return result;
    }

    private static String isoUtcNow() {
        return OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
