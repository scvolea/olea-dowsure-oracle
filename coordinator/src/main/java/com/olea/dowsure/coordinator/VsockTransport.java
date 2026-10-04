package com.olea.dowsure.coordinator;

import java.io.IOException;

/**
 * Low-level byte exchange with the enclave over a single connection. Split out
 * from {@link VsockEnclaveClient} so the half-close-before-read ordering can be
 * verified in a unit test with a fake, without opening a real AF_VSOCK socket.
 *
 * <p>Implementations MUST, in order: connect to {@code (cid, port)}, send the
 * whole request, half-close the write side (shutdown-write), read the full
 * response, then close.
 */
@FunctionalInterface
public interface VsockTransport {
    byte[] exchange(int cid, int port, byte[] request) throws IOException;
}
