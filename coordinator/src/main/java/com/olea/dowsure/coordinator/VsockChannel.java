package com.olea.dowsure.coordinator;

import java.io.IOException;

/**
 * Minimal socket-like seam used by {@link AfVsockTransport} so the
 * write / read / close framing can be verified in a unit test with a fake.
 *
 * <p>The wire protocol is explicit LENGTH-PREFIX framing (NOT half-close/EOF):
 * each message is a 4-byte big-endian unsigned length followed by that many
 * payload bytes. There is deliberately NO {@code shutdownWrite}/half-close on
 * this seam — AF_VSOCK does not reliably support {@code SHUT_WR} half-close via
 * junixsocket, which is the confirmed root cause of the transport failure this
 * seam was reshaped to fix.
 */
interface VsockChannel extends AutoCloseable {
    /** Writes all of {@code payload} to the channel and flushes. No framing is applied here. */
    void write(byte[] payload) throws IOException;

    /**
     * Reads EXACTLY {@code n} bytes, looping until satisfied. Throws
     * {@link java.io.EOFException} if the stream ends before {@code n} bytes arrive.
     */
    byte[] readExactly(int n) throws IOException;

    @Override
    void close() throws IOException;
}
