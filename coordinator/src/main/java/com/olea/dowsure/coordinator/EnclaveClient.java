package com.olea.dowsure.coordinator;

import java.util.Map;

/**
 * Typed adapter for the Nitro enclave vsock boundary. The coordinator talks to
 * the enclave only through this interface, so tests can inject a fake transport
 * without opening a real AF_VSOCK socket.
 */
public interface EnclaveClient {
    /**
     * Sends {@code request} to the enclave at {@code (cid, port)} and returns the
     * {@code evidence} map when the enclave reports {@code ok:true}. Throws when
     * the enclave reports {@code ok:false} (or omits {@code ok}), carrying the
     * enclave {@code error} (defaulting to {@code ENCLAVE_FAILED}).
     */
    Map<String, Object> invoke(int cid, int port, Map<String, Object> request);
}
