package com.olea.dowsure.enclave;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline framing + resilience tests for {@link VsockServer#handleOne}. These drive the
 * per-connection logic through an in-memory {@link VsockServer.Connection} fake so no real
 * AF_VSOCK socket is opened.
 *
 * <p>The wire protocol is explicit LENGTH-PREFIX framing (4-byte big-endian length, then the
 * payload) on BOTH request and response — no EOF/half-close dependency, which was the confirmed
 * AF_VSOCK root cause. These tests prove: (a) the happy path reads the framed request and writes
 * a framed response, (b) an empty connection is a no-op (handler never runs), (c) a truncated
 * header or body surfaces as a clean {@link EOFException}, (d) an oversized declared length is
 * rejected, and (e) handler/write failures propagate so {@code serve()}'s catch(Throwable) can
 * contain them.
 */
class VsockServerTest {

    /** Prepends a 4-byte big-endian length prefix to {@code payload}. */
    private static byte[] frame(byte[] payload) {
        byte[] framed = new byte[4 + payload.length];
        framed[0] = (byte) (payload.length >>> 24);
        framed[1] = (byte) (payload.length >>> 16);
        framed[2] = (byte) (payload.length >>> 8);
        framed[3] = (byte) payload.length;
        System.arraycopy(payload, 0, framed, 4, payload.length);
        return framed;
    }

    /** Reads a 4-byte big-endian length prefix then that many payload bytes from {@code written}. */
    private static byte[] unframe(byte[] written) {
        int len = ((written[0] & 0xFF) << 24) | ((written[1] & 0xFF) << 16)
                | ((written[2] & 0xFF) << 8) | (written[3] & 0xFF);
        byte[] payload = new byte[len];
        System.arraycopy(written, 4, payload, 0, len);
        return payload;
    }

    @Test
    void normalRequestReadsFramedRequestAndWritesFramedResponse() throws Exception {
        byte[] requestJson = "{\"source\":\"amazon\"}".getBytes(StandardCharsets.UTF_8);
        FakeConnection connection = new FakeConnection(frame(requestJson));
        AtomicBoolean sawRequest = new AtomicBoolean(false);

        VsockServer.handleOne(connection, request -> {
            sawRequest.set(request.equals("{\"source\":\"amazon\"}"));
            return "{\"ok\":true}";
        });

        assertTrue(sawRequest.get(), "handler must receive the exact unframed request JSON");
        byte[] response = unframe(connection.writtenBytes());
        assertArrayEquals("{\"ok\":true}".getBytes(StandardCharsets.UTF_8), response);
    }

    @Test
    void emptyConnectionDoesNotInvokeHandlerOrWrite() throws Exception {
        FakeConnection connection = new FakeConnection(new byte[0]);
        AtomicBoolean handlerInvoked = new AtomicBoolean(false);

        VsockServer.handleOne(connection, request -> {
            handlerInvoked.set(true);
            return "should-not-be-used";
        });

        assertFalse(handlerInvoked.get(), "handler must not be invoked for an empty connection");
        assertEquals(0, connection.writtenBytes().length, "nothing should be written for empty input");
    }

    @Test
    void truncatedHeaderThrowsEof() {
        // Started a header (1 byte) but the rest never arrives -> clean EOFException.
        FakeConnection connection = new FakeConnection(new byte[] {0});

        assertThrows(EOFException.class,
                () -> VsockServer.handleOne(connection, request -> "{\"ok\":true}"));
    }

    @Test
    void truncatedBodyThrowsEof() {
        // Header declares 10 bytes but only 3 follow -> clean EOFException.
        FakeConnection connection = new FakeConnection(new byte[] {0, 0, 0, 10, 'a', 'b', 'c'});

        assertThrows(EOFException.class,
                () -> VsockServer.handleOne(connection, request -> "{\"ok\":true}"));
    }

    @Test
    void oversizedLengthIsRejected() {
        // Declared length > MAX_FRAME_BYTES must be rejected before allocating the body.
        FakeConnection connection = new FakeConnection(new byte[] {(byte) 0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});

        IOException error = assertThrows(IOException.class,
                () -> VsockServer.handleOne(connection, request -> "{\"ok\":true}"));
        assertEquals("VSOCK_FRAME_TOO_LARGE", error.getMessage());
    }

    @Test
    void handlerThatThrowsPropagatesAndWritesNothing() {
        FakeConnection connection = new FakeConnection(frame("anything".getBytes(StandardCharsets.UTF_8)));

        assertThrows(RuntimeException.class,
                () -> VsockServer.handleOne(connection, request -> {
                    throw new RuntimeException("boom");
                }));

        // Nothing written: serve()'s catch(Throwable) is what contains this, so the loop continues.
        assertEquals(0, connection.writtenBytes().length);
    }

    @Test
    void writeFailurePropagatesPerConnection() {
        FakeConnection connection = new FakeConnection(frame("req".getBytes(StandardCharsets.UTF_8)), true);

        assertThrows(IOException.class,
                () -> VsockServer.handleOne(connection, request -> "{\"ok\":true}"));
    }

    /** In-memory {@link VsockServer.Connection}: request bytes in, response captured out. */
    private static final class FakeConnection implements VsockServer.Connection {
        private final ByteArrayInputStream in;
        private final OutputStream out;
        private final ByteArrayOutputStream captured;

        FakeConnection(byte[] request) {
            this(request, false);
        }

        FakeConnection(byte[] request, boolean failOnWrite) {
            this.in = new ByteArrayInputStream(request);
            if (failOnWrite) {
                this.captured = null;
                this.out = new OutputStream() {
                    @Override
                    public void write(int b) throws IOException {
                        throw new IOException("WRITE_FAILED");
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        throw new IOException("WRITE_FAILED");
                    }
                };
            } else {
                this.captured = new ByteArrayOutputStream();
                this.out = this.captured;
            }
        }

        @Override
        public InputStream getInputStream() {
            return in;
        }

        @Override
        public OutputStream getOutputStream() {
            return out;
        }

        @Override
        public void close() {
            // no-op
        }

        byte[] writtenBytes() {
            assertTrue(captured != null, "writtenBytes() is only valid for a capturing connection");
            return captured.toByteArray();
        }
    }
}
