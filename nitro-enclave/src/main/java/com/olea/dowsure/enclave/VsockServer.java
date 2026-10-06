package com.olea.dowsure.enclave;

import org.newsclub.net.unix.vsock.AFVSOCKServerSocket;
import org.newsclub.net.unix.AFVSOCKSocketAddress;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class VsockServer {
    /** Reject any declared request frame larger than this to avoid unbounded allocation. */
    static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;

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
        InputStream in = connection.getInputStream();

        // Read the 4-byte big-endian length prefix. A client that connects and sends nothing
        // yields an immediate EOF on the first header byte: treat that as an empty connection
        // (close + continue) rather than crashing. A header that starts but ends short is a
        // truncated frame and surfaces as EOFException (contained by serve()'s catch(Throwable)).
        byte[] header = new byte[4];
        int first = in.read(header, 0, 1);
        if (first < 0) {
            return; // client sent nothing: do not invoke the handler
        }
        readExactly(in, header, 1, 3); // the rest of the header must arrive
        long len = ((long) (header[0] & 0xFF) << 24)
                | ((header[1] & 0xFF) << 16)
                | ((header[2] & 0xFF) << 8)
                | (header[3] & 0xFF);
        if (len > MAX_FRAME_BYTES) {
            throw new IOException("VSOCK_FRAME_TOO_LARGE");
        }

        byte[] request = new byte[(int) len];
        readExactly(in, request, 0, (int) len);

        byte[] response = handler.handle(new String(request, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);

        OutputStream out = connection.getOutputStream();
        out.write(response.length >>> 24);
        out.write(response.length >>> 16);
        out.write(response.length >>> 8);
        out.write(response.length);
        out.write(response);
        out.flush();
    }

    /** Reads EXACTLY {@code n} bytes into {@code buffer} at {@code off}, or throws {@link EOFException}. */
    private static void readExactly(InputStream in, byte[] buffer, int off, int n) throws IOException {
        int read = 0;
        while (read < n) {
            int r = in.read(buffer, off + read, n - read);
            if (r < 0) {
                throw new EOFException("VSOCK_STREAM_EOF");
            }
            read += r;
        }
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
