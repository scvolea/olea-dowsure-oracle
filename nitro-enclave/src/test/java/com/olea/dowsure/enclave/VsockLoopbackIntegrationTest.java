package com.olea.dowsure.enclave;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real connected-socket integration proving the enclave's {@link VsockServer#handleOne} framing is
 * byte-compatible with the coordinator's AfVsockTransport framing WITHOUT needing AF_VSOCK.
 *
 * <p>A loopback TCP {@link ServerSocket} on 127.0.0.1 feeds the REAL {@code handleOne} through the
 * {@link VsockServer.Connection} seam. A client peer writes {@code [4-byte big-endian len][request]}
 * — the identical bytes the coordinator's {@code AfVsockTransport.exchange} writes — and reads back
 * {@code [4-byte big-endian len][response]}. The client NEVER half-closes its write side (the
 * confirmed AF_VSOCK failure mode); the server replies on the still-open full-duplex stream.
 *
 * <p>The {@link #SHARED_REQUEST}/{@link #SHARED_RESPONSE} vectors match the coordinator module's
 * VsockLoopbackIntegrationTest byte-for-byte, so both framings are proven to agree on the wire.
 */
class VsockLoopbackIntegrationTest {

    /** Shared byte-level vector, identical to the coordinator module's loopback test. */
    static final byte[] SHARED_REQUEST =
            "{\"requestId\":\"r1\",\"source\":\"amazon\"}".getBytes(StandardCharsets.UTF_8);
    static final byte[] SHARED_RESPONSE =
            "{\"ok\":true,\"evidence\":{\"manifestDigest\":\"abc\"}}".getBytes(StandardCharsets.UTF_8);

    @Test
    void handleOneFramingRoundTripsOverRealSocket() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();

            // Enclave side: the REAL VsockServer.handleOne over the accepted socket.
            Future<String> handlerSawRequest = pool.submit(() -> {
                try (Socket accepted = server.accept()) {
                    String[] captured = new String[1];
                    VsockServer.handleOne(new SocketConnectionPeer(accepted), request -> {
                        captured[0] = request;
                        return new String(SHARED_RESPONSE, StandardCharsets.UTF_8);
                    });
                    return captured[0];
                }
            });

            // Coordinator stand-in: write [len][request], read [len][response]. No half-close.
            try (Socket client = new Socket("127.0.0.1", port)) {
                OutputStream out = client.getOutputStream();
                out.write(SHARED_REQUEST.length >>> 24);
                out.write(SHARED_REQUEST.length >>> 16);
                out.write(SHARED_REQUEST.length >>> 8);
                out.write(SHARED_REQUEST.length);
                out.write(SHARED_REQUEST);
                out.flush();

                DataInputStream in = new DataInputStream(client.getInputStream());
                int len = in.readInt();
                byte[] response = new byte[len];
                in.readFully(response);
                assertArrayEquals(SHARED_RESPONSE, response, "coordinator must read back the exact response");
            }

            String requestSeen = handlerSawRequest.get(5, TimeUnit.SECONDS);
            assertTrue(new String(SHARED_REQUEST, StandardCharsets.UTF_8).equals(requestSeen),
                    "handler must receive the exact unframed request JSON");
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /** {@link VsockServer.Connection} over a plain TCP {@link Socket} — same seam the real adapter fills. */
    private static final class SocketConnectionPeer implements VsockServer.Connection {
        private final Socket socket;

        SocketConnectionPeer(Socket socket) {
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
            // no-op: the test owns and closes the socket
        }
    }
}
