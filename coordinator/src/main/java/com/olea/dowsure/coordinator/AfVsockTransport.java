package com.olea.dowsure.coordinator;

import org.newsclub.net.unix.AFVSOCKSocketAddress;
import org.newsclub.net.unix.vsock.AFVSOCKSocket;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Real AF_VSOCK transport using junixsocket — the SAME library/version the
 * enclave's {@code VsockServer} binds with, so the framing matches exactly.
 *
 * <p>Wire protocol is explicit LENGTH-PREFIX framing (NO socket half-close):
 * write a 4-byte big-endian unsigned length prefix followed by the request
 * bytes and flush, then read a 4-byte big-endian length prefix followed by
 * EXACTLY that many response bytes. AF_VSOCK does not reliably support
 * {@code SHUT_WR} half-close via junixsocket, so {@code shutdownOutput} is
 * deliberately never called. The ordering lives in
 * {@link #exchange(VsockChannel, byte[])} against the {@link VsockChannel} seam
 * so it is unit-testable with a fake.
 */
public final class AfVsockTransport implements VsockTransport {

    /** Reject any declared frame larger than this to avoid unbounded allocation. */
    static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;

    @Override
    public byte[] exchange(int cid, int port, byte[] request) throws IOException {
        AFVSOCKSocketAddress address = AFVSOCKSocketAddress.ofPortAndCID(port, cid);
        try (VsockChannel channel = new SocketChannelAdapter(AFVSOCKSocket.connectTo(address))) {
            return exchange(channel, request);
        }
    }

    /**
     * Length-prefixed byte exchange over a channel: write {@code [len][request]},
     * then read {@code [len][response]} — no half-close anywhere. Package-private
     * so a test can assert the framing with a fake channel.
     */
    static byte[] exchange(VsockChannel channel, byte[] request) throws IOException {
        channel.write(frame(request));
        return readFrame(channel);
    }

    /** Prepends a 4-byte big-endian length prefix to {@code payload}. */
    static byte[] frame(byte[] payload) {
        byte[] framed = new byte[4 + payload.length];
        framed[0] = (byte) (payload.length >>> 24);
        framed[1] = (byte) (payload.length >>> 16);
        framed[2] = (byte) (payload.length >>> 8);
        framed[3] = (byte) payload.length;
        System.arraycopy(payload, 0, framed, 4, payload.length);
        return framed;
    }

    /** Reads a 4-byte big-endian length prefix then exactly that many payload bytes. */
    static byte[] readFrame(VsockChannel channel) throws IOException {
        byte[] header = channel.readExactly(4);
        long len = ((long) (header[0] & 0xFF) << 24)
                | ((header[1] & 0xFF) << 16)
                | ((header[2] & 0xFF) << 8)
                | (header[3] & 0xFF);
        if (len > MAX_FRAME_BYTES) {
            throw new IOException("ENCLAVE_FRAME_TOO_LARGE");
        }
        return channel.readExactly((int) len);
    }

    /** Adapts a junixsocket {@link AFVSOCKSocket} to the {@link VsockChannel} seam. */
    private static final class SocketChannelAdapter implements VsockChannel {
        private final AFVSOCKSocket socket;

        SocketChannelAdapter(AFVSOCKSocket socket) {
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

    /** Loops on {@code read} until exactly {@code n} bytes are read or EOF is hit. */
    static byte[] readExactly(InputStream in, int n) throws IOException {
        byte[] buffer = new byte[n];
        int offset = 0;
        while (offset < n) {
            int read = in.read(buffer, offset, n - offset);
            if (read < 0) {
                throw new EOFException("ENCLAVE_STREAM_EOF");
            }
            offset += read;
        }
        return buffer;
    }
}
