package com.olea.dowsure.enclave;

import org.newsclub.net.unix.vsock.AFVSOCKServerSocket;
import org.newsclub.net.unix.AFVSOCKSocketAddress;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class VsockServer {
    @FunctionalInterface
    public interface RequestHandler {
        String handle(String request);
    }

    public void serve(int cid, int port, RequestHandler handler) throws IOException {
        try (AFVSOCKServerSocket server = AFVSOCKServerSocket.bindOn(AFVSOCKSocketAddress.ofPortAndCID(port, cid))) {
            while (true) {
                // A single bad connection must NEVER take down the enclave: accept + per-connection
                // handling is wrapped so any Throwable is contained and the loop continues.
                try (var socket = server.accept()) {
                    handleOne(new SocketConnection(socket), handler);
                } catch (Throwable connectionError) {
                    // Code-as-message only; never log request bytes, headers, payloads, tokens, or PII.
                    System.err.println("VSOCK_CONNECTION_ERROR " + connectionError.getClass().getSimpleName());
                }
            }
        }
    }

    /**
     * Handle exactly one connection: read the request, invoke the handler, write the response.
     * Package-private so it can be unit-tested offline against an in-memory {@link Connection}
     * without opening a real AF_VSOCK socket. Any Throwable thrown here is contained by the
     * per-connection {@code catch} in {@link #serve}, so the accept loop continues.
     */
    static void handleOne(Connection connection, RequestHandler handler) throws IOException {
        byte[] request = connection.getInputStream().readNBytes(1024 * 1024);
        if (request.length == 0) {
            return; // client sent nothing: do not invoke the handler
        }
        byte[] response = handler.handle(new String(request, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);
        OutputStream out = connection.getOutputStream();
        out.write(response);
        out.flush();
    }

    /**
     * Per-connection seam (same style as {@link SourceTransport}) so {@link #handleOne} can be
     * driven by in-memory fakes in tests without a real AF_VSOCK socket.
     */
    interface Connection extends AutoCloseable {
        InputStream getInputStream() throws IOException;

        OutputStream getOutputStream() throws IOException;

        @Override
        void close() throws IOException;
    }

    /** Adapter over the real AF_VSOCK socket returned by {@code server.accept()}. */
    static final class SocketConnection implements Connection {
        private final Socket socket;

        SocketConnection(Socket socket) {
            this.socket = socket;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return socket.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return socket.getOutputStream();
        }

        @Override
        public void close() {
            // no-op: the outer try-with-resources in serve() owns and closes the socket exactly once
        }
    }
}
