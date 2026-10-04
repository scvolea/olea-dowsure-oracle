package com.olea.dowsure.coordinator;

import org.newsclub.net.unix.AFVSOCKSocketAddress;
import org.newsclub.net.unix.vsock.AFVSOCKSocket;

import java.io.IOException;

/**
 * Real AF_VSOCK transport using junixsocket — the SAME library/version the
 * enclave's {@code VsockServer} binds with, so the framing matches exactly.
 *
 * <p>Sequence mirrors the Python coordinator's {@code invoke_enclave}: connect,
 * {@code sendall}, {@code shutdown(SHUT_WR)} (half-close write), read the full
 * response, close. The ordering lives in {@link #exchange(VsockChannel, byte[])}
 * against the {@link VsockChannel} seam so it is unit-testable with a fake.
 */
public final class AfVsockTransport implements VsockTransport {

    @Override
    public byte[] exchange(int cid, int port, byte[] request) throws IOException {
        AFVSOCKSocketAddress address = AFVSOCKSocketAddress.ofPortAndCID(port, cid);
        try (VsockChannel channel = new SocketChannelAdapter(AFVSOCKSocket.connectTo(address))) {
            return exchange(channel, request);
        }
    }

    /**
     * Byte exchange over a channel: send the whole request, half-close the write
     * side BEFORE reading, then read the full response. Package-private so a test
     * can assert the ordering with a fake channel.
     */
    static byte[] exchange(VsockChannel channel, byte[] request) throws IOException {
        channel.sendAll(request);
        channel.shutdownWrite();
        return channel.readAll();
    }

    /** Adapts a junixsocket {@link AFVSOCKSocket} to the {@link VsockChannel} seam. */
    private static final class SocketChannelAdapter implements VsockChannel {
        private final AFVSOCKSocket socket;

        SocketChannelAdapter(AFVSOCKSocket socket) {
            this.socket = socket;
        }

        @Override
        public void sendAll(byte[] payload) throws IOException {
            socket.getOutputStream().write(payload);
            socket.getOutputStream().flush();
        }

        @Override
        public void shutdownWrite() throws IOException {
            socket.shutdownOutput();
        }

        @Override
        public byte[] readAll() throws IOException {
            return socket.getInputStream().readAllBytes();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
