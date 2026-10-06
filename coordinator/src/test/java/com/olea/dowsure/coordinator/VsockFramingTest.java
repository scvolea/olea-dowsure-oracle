package com.olea.dowsure.coordinator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the AF_VSOCK exchange uses explicit LENGTH-PREFIX framing (NO half-close)
 * and that {@link VsockEnclaveClient} returns {@code evidence} on {@code ok:true} and
 * fails closed on {@code ok:false}/missing {@code ok}.
 *
 * <p>The confirmed root cause was that {@code shutdownOutput} throws on AF_VSOCK via
 * junixsocket; these tests pin that no half-close method exists on the {@link VsockChannel}
 * seam and that the client both writes and reads {@code [4-byte big-endian len][payload]}.
 */
class VsockFramingTest {
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Fake channel over an in-memory byte stream. Captures everything the transport writes
     * and serves the pre-seeded response bytes back through {@code readExactly}, so the test
     * exercises the REAL length-prefix parsing in {@link AfVsockTransport}.
     */
    private static final class FakeChannel implements VsockChannel {
        final List<String> calls = new ArrayList<>();
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        private final ByteArrayInputStream response;

        FakeChannel(byte[] framedResponse) {
            this.response = new ByteArrayInputStream(framedResponse);
        }

        @Override
        public void write(byte[] payload) {
            calls.add("write");
            written.write(payload, 0, payload.length);
        }

        @Override
        public byte[] readExactly(int n) throws IOException {
            calls.add("readExactly");
            return AfVsockTransport.readExactly(response, n);
        }

        @Override
        public void close() {
            calls.add("close");
        }
    }

    @Test
    void writesLengthPrefixedRequestAndParsesLengthPrefixedResponse() throws IOException {
        byte[] responseJson = "{\"ok\":true,\"evidence\":{\"manifestDigest\":\"abc\"}}"
                .getBytes(StandardCharsets.UTF_8);
        FakeChannel channel = new FakeChannel(AfVsockTransport.frame(responseJson));

        byte[] requestJson = "{\"requestId\":\"r1\"}".getBytes(StandardCharsets.UTF_8);
        byte[] out = AfVsockTransport.exchange(channel, requestJson);

        // Response is parsed back to the exact unframed JSON bytes.
        assertArrayEquals(responseJson, out);

        // The request went out as [4-byte big-endian len][payload].
        byte[] wire = channel.written.toByteArray();
        assertArrayEquals(AfVsockTransport.frame(requestJson), wire);
        int declaredLen = ((wire[0] & 0xFF) << 24) | ((wire[1] & 0xFF) << 16)
                | ((wire[2] & 0xFF) << 8) | (wire[3] & 0xFF);
        assertEquals(requestJson.length, declaredLen, "length prefix must equal payload size");
        assertArrayEquals(requestJson, Arrays.copyOfRange(wire, 4, wire.length));

        // Write happens before any read; nothing resembling a half-close is invoked.
        assertEquals("write", channel.calls.get(0));
        assertFalse(channel.calls.contains("shutdownWrite"), "no half-close may be used");
    }

    @Test
    void noHalfCloseMethodOnChannelSeam() {
        boolean hasHalfClose = Arrays.stream(VsockChannel.class.getMethods())
                .map(Method::getName)
                .anyMatch(name -> name.equals("shutdownWrite") || name.equals("shutdownOutput"));
        assertFalse(hasHalfClose, "VsockChannel must expose no half-close method");
    }

    @Test
    void rejectsOversizedResponseFrame() {
        // A header declaring more than MAX_FRAME_BYTES must be rejected before allocating.
        byte[] oversized = new byte[] {(byte) 0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        FakeChannel channel = new FakeChannel(oversized);

        IOException error = assertThrows(IOException.class,
                () -> AfVsockTransport.exchange(channel, "req".getBytes(StandardCharsets.UTF_8)));
        assertEquals("ENCLAVE_FRAME_TOO_LARGE", error.getMessage());
    }

    @Test
    void truncatedResponseBodyThrowsEof() {
        // Header says 10 bytes but only 3 follow: readExactly must throw EOFException.
        byte[] badFrame = new byte[] {0, 0, 0, 10, 'a', 'b', 'c'};
        FakeChannel channel = new FakeChannel(badFrame);

        assertThrows(EOFException.class,
                () -> AfVsockTransport.exchange(channel, "req".getBytes(StandardCharsets.UTF_8)));
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
