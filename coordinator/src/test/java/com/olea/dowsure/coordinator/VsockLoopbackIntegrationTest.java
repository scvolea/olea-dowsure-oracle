package com.olea.dowsure.coordinator;

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
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Real connected-socket integration proving the coordinator's {@link AfVsockTransport} framing
 * is byte-compatible with the enclave's VsockServer framing WITHOUT needing AF_VSOCK.
 *
 * <p>A loopback TCP {@link ServerSocket} on 127.0.0.1 stands in for the enclave. The server peer
 * speaks the EXACT agreed wire protocol — read {@code [4-byte big-endian len][request]}, write
 * {@code [4-byte big-endian len][response]} — the identical bytes the enclave's
 * {@code VsockServer.handleOne} reads/writes (see the shared vector in {@link #SHARED_REQUEST}/
 * {@link #SHARED_RESPONSE}, asserted on both sides of the repo). The transport runs its REAL
 * {@code exchange} through a {@link VsockChannel} adapter over the client socket.
 *
 * <p>Critically, the server here NEVER observes a half-close: it reads exactly the declared number
 * of request bytes and immediately replies on the SAME still-open stream. If the transport called
 * {@code shutdownOutput}, this round-trip would still pass, so the no-half-close guarantee is pinned
 * structurally by {@code VsockFramingTest.noHalfCloseMethodOnChannelSeam} and the absence of any
 * half-close call in {@link AfVsockTransport} — this test proves the positive: full-duplex framing works.
 */
class VsockLoopbackIntegrationTest {

    /** Shared byte-level vector both the coordinator and enclave module tests agree on. */
    static final byte[] SHARED_REQUEST =
            "{\"requestId\":\"r1\",\"source\":\"amazon\"}".getBytes(StandardCharsets.UTF_8);
    static final byte[] SHARED_RESPONSE =
            "{\"ok\":true,\"evidence\":{\"manifestDigest\":\"abc\"}}".getBytes(StandardCharsets.UTF_8);

    @Test
    void transportFramingRoundTripsOverRealSocket() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();

            // Enclave stand-in: speaks the exact VsockServer wire protocol.
            Future<byte[]> serverSawRequest = pool.submit(() -> {
                try (Socket accepted = server.accept()) {
                    DataInputStream in = new DataInputStream(accepted.getInputStream());
                    int len = in.readInt();
                    byte[] request = new byte[len];
                    in.readFully(request);

                    OutputStream out = accepted.getOutputStream();
                    out.write(SHARED_RESPONSE.length >>> 24);
                    out.write(SHARED_RESPONSE.length >>> 16);
                    out.write(SHARED_RESPONSE.length >>> 8);
                    out.write(SHARED_RESPONSE.length);
                    out.write(SHARED_RESPONSE);
                    out.flush();
                    return request;
                }
            });

            try (Socket client = new Socket("127.0.0.1", port)) {
                byte[] response = AfVsockTransport.exchange(new TcpChannel(client), SHARED_REQUEST);
                assertArrayEquals(SHARED_RESPONSE, response, "coordinator must read back the exact response");
            }

            byte[] requestOnWire = serverSawRequest.get(5, TimeUnit.SECONDS);
            assertArrayEquals(SHARED_REQUEST, requestOnWire,
                    "enclave stand-in must receive the exact length-prefixed request payload");
        } finally {
            pool.shutdownNow();
            assertEquals(true, pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /** {@link VsockChannel} over a plain TCP {@link Socket} — the same seam the real adapter fills. */
    private static final class TcpChannel implements VsockChannel {
        private final Socket socket;

        TcpChannel(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void write(byte[] payload) throws IOException {
            socket.getOutputStream().write(payload);
            socket.getOutputStream().flush();
        }

        @Override
        public byte[] readExactly(int n) throws IOException {
            return AfVsockTransport.readExactly(socket.getInputStream(), n);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
