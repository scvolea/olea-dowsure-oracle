package com.olea.dowsure.coordinator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * {@link EnclaveClient} over a {@link VsockTransport}. Serializes the request to
 * JSON, exchanges bytes with the enclave, parses the response, and enforces the
 * {@code ok} contract — the coordinator's fail-closed point.
 *
 * <p>Mirrors the Python {@code invoke_enclave}: on {@code ok:true} it returns the
 * {@code evidence} map; on {@code ok:false} (or a missing {@code ok}) it throws
 * carrying the enclave {@code error}, defaulting to {@code ENCLAVE_FAILED}.
 */
public final class VsockEnclaveClient implements EnclaveClient {
    private final VsockTransport transport;
    private final ObjectMapper mapper;

    public VsockEnclaveClient(VsockTransport transport, ObjectMapper mapper) {
        this.transport = transport;
        this.mapper = mapper;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> invoke(int cid, int port, Map<String, Object> request) {
        byte[] response;
        try {
            byte[] payload = mapper.writeValueAsBytes(request);
            response = transport.exchange(cid, port, payload);
        } catch (IOException error) {
            throw new IllegalStateException("ENCLAVE_TRANSPORT_FAILED", error);
        }
        Map<String, Object> result;
        try {
            result = mapper.readValue(new String(response, StandardCharsets.UTF_8), new TypeReference<Map<String, Object>>() {
            });
        } catch (IOException error) {
            throw new IllegalStateException("ENCLAVE_RESPONSE_INVALID", error);
        }
        Object ok = result.get("ok");
        if (!Boolean.TRUE.equals(ok)) {
            Object error = result.get("error");
            throw new IllegalStateException(error == null ? "ENCLAVE_FAILED" : String.valueOf(error));
        }
        return (Map<String, Object>) result.get("evidence");
    }
}
