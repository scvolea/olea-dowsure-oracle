package com.olea.dowsure.coordinator;

import java.io.IOException;

/**
 * Minimal socket-like seam used by {@link AfVsockTransport} so the
 * write / half-close / read / close ordering can be verified in a unit test
 * with a fake, mirroring the Python coordinator test's {@code FakeSocket}.
 */
interface VsockChannel extends AutoCloseable {
    void sendAll(byte[] payload) throws IOException;

    /** Half-closes the write side (equivalent to {@code socket.shutdown(SHUT_WR)}). */
    void shutdownWrite() throws IOException;

    byte[] readAll() throws IOException;

    @Override
    void close() throws IOException;
}
