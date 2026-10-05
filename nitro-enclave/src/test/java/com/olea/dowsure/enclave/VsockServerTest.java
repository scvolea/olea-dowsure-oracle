package com.olea.dowsure.enclave;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline resilience tests for {@link VsockServer#handleOne}. These drive the per-connection
 * logic through an in-memory {@link VsockServer.Connection} fake so no real AF_VSOCK socket is
 * opened. The guarantee that any single-connection Throwable is contained and the accept loop
 * continues lives in {@code serve()}'s {@code catch (Throwable)}; these tests prove the three
 * ways {@code handleOne} can misbehave are (a) a correct response on the happy path, (b) a
 * no-op on empty input, and (c) a propagated throwable (handler/write failure) that the loop's
 * catch then swallows.
 */
class VsockServerTest {

    @Test
    void normalRequestWritesHandlerResponse() throws Exception {
        FakeConnection connection = new FakeConnection("{\"source\":\"amazon\"}".getBytes(StandardCharsets.UTF_8));

        VsockServer.handleOne(connection, request -> "{\"ok\":true}");

        assertEquals("{\"ok\":true}", connection.written());
    }

    @Test
    void handlerThatThrowsPropagatesAndWritesNothing() {
        FakeConnection connection = new FakeConnection("anything".getBytes(StandardCharsets.UTF_8));

        assertThrows(RuntimeException.class,
                () -> VsockServer.handleOne(connection, request -> {
                    throw new RuntimeException("boom");
                }));

        // Nothing written: serve()'s catch(Throwable) is what contains this, so the loop continues.
        assertEquals("", connection.written());
    }

    @Test
    void emptyInputDoesNotInvokeHandlerOrWrite() throws Exception {
        FakeConnection connection = new FakeConnection(new byte[0]);
        AtomicBoolean handlerInvoked = new AtomicBoolean(false);

        VsockServer.handleOne(connection, request -> {
            handlerInvoked.set(true);
            return "should-not-be-used";
        });

        assertFalse(handlerInvoked.get(), "handler must not be invoked for empty input");
        assertEquals("", connection.written(), "nothing should be written for empty input");
    }

    @Test
    void writeFailurePropagatesPerConnection() {
        FakeConnection connection = new FakeConnection("req".getBytes(StandardCharsets.UTF_8), true);

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

        String written() {
            assertTrue(captured != null, "written() is only valid for a capturing connection");
            return captured.toString(StandardCharsets.UTF_8);
        }
    }
}
