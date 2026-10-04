package com.olea.dowsure.coordinator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ports {@code tests/test_coordinator.py}: the vsock exchange MUST half-close the
 * write side before reading, and {@link VsockEnclaveClient} returns {@code evidence}
 * on {@code ok:true} and fails closed on {@code ok:false}/missing {@code ok}.
 */
class VsockFramingTest {
    private final ObjectMapper mapper = new ObjectMapper();

    /** Fake channel recording the order of operations (mirrors the Python FakeSocket). */
    private static final class FakeChannel implements VsockChannel {
        final List<String> calls = new ArrayList<>();
        private final byte[] response;

        FakeChannel(byte[] response) {
            this.response = response;
        }

        @Override
        public void sendAll(byte[] payload) {
            calls.add("sendAll");
        }

        @Override
        public void shutdownWrite() {
            calls.add("shutdownWrite");
        }

        @Override
        public byte[] readAll() {
            calls.add("readAll");
            return response;
        }

        @Override
        public void close() {
            calls.add("close");
        }
    }

    @Test
    void halfClosesWriteSideBeforeRead() throws IOException {
        byte[] response = "{\"ok\":true,\"evidence\":{\"manifestDigest\":\"abc\"}}"
                .getBytes(StandardCharsets.UTF_8);
        FakeChannel channel = new FakeChannel(response);

        byte[] out = AfVsockTransport.exchange(channel, "{\"requestId\":\"r1\"}".getBytes(StandardCharsets.UTF_8));

        assertEquals(response.length, out.length);
        assertTrue(channel.calls.indexOf("shutdownWrite") < channel.calls.indexOf("readAll"),
                "write side must be half-closed before reading");
        assertTrue(channel.calls.indexOf("sendAll") < channel.calls.indexOf("shutdownWrite"),
                "request must be sent before half-close");
    }

    @Test
    void returnsEvidenceOnOkTrue() {
        VsockTransport transport = (cid, port, request) ->
                "{\"ok\":true,\"evidence\":{\"manifestDigest\":\"abc\"}}".getBytes(StandardCharsets.UTF_8);
        VsockEnclaveClient client = new VsockEnclaveClient(transport, mapper);

        Map<String, Object> evidence = client.invoke(16, 5005, Map.of("requestId", "r1"));

        assertEquals("abc", evidence.get("manifestDigest"));
    }

    @Test
    void failsClosedOnOkFalseWithError() {
        VsockTransport transport = (cid, port, request) ->
                "{\"ok\":false,\"error\":\"POLICY_SCOPE_INVALID\"}".getBytes(StandardCharsets.UTF_8);
        VsockEnclaveClient client = new VsockEnclaveClient(transport, mapper);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.invoke(16, 5005, Map.of("requestId", "r1")));
        assertEquals("POLICY_SCOPE_INVALID", error.getMessage());
    }

    @Test
    void failsClosedWithDefaultWhenOkFalseAndNoError() {
        VsockTransport transport = (cid, port, request) ->
                "{\"ok\":false}".getBytes(StandardCharsets.UTF_8);
        VsockEnclaveClient client = new VsockEnclaveClient(transport, mapper);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.invoke(16, 5005, Map.of("requestId", "r1")));
        assertEquals("ENCLAVE_FAILED", error.getMessage());
    }

    @Test
    void failsClosedWhenOkMissing() {
        VsockTransport transport = (cid, port, request) ->
                "{\"evidence\":{\"manifestDigest\":\"abc\"}}".getBytes(StandardCharsets.UTF_8);
        VsockEnclaveClient client = new VsockEnclaveClient(transport, mapper);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.invoke(16, 5005, Map.of("requestId", "r1")));
        assertEquals("ENCLAVE_FAILED", error.getMessage());
    }
}
