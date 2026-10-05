package com.olea.dowsure.enclave;

import org.newsclub.net.unix.AFVSOCKSocketAddress;
import org.newsclub.net.unix.vsock.AFVSOCKSocket;

import java.io.IOException;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

/**
 * Production {@link SourceTransport} for the Nitro Enclave: egress is AF_VSOCK
 * only (no network interface inside the enclave), so instead of dialing
 * {@code host:port} directly this connects to the PARENT instance's vsock-proxy
 * (relay), which forwards bytes transparently to the real {@code host:443}.
 *
 * <p>Because the relay is a transparent byte pump, the {@code SSLSocket} that
 * {@link SourceTlsClient} layers on top still negotiates TLS SNI and verifies
 * the certificate hostname against {@code entry.host()} end-to-end — this
 * transport only supplies the base {@link Socket}.
 *
 * <p>The dial target is a vsock {@code (CID, port)}: the relay CID is the
 * parent/host (default {@code 3}, {@code VMADDR_CID_HOST}) and the vsock port is
 * resolved from a per-host map. Resolution is fail-closed — an unmapped host
 * throws {@code RELAY_PORT_UNMAPPED} with NO plain-TCP fallback. Nothing about
 * the host, bytes, or headers is logged.
 *
 * <p>Mirrors the coordinator's {@code AfVsockTransport} connect pattern and
 * {@link SourceRegistry}'s injectable-env seam so the config/mapping logic is
 * unit-testable offline without opening a real vsock.
 */
public final class AfVsockSourceTransport implements SourceTransport {

    /** CID of the parent/host running the vsock-proxy (sources env SOURCE_RELAY_CID). */
    static final String RELAY_CID_ENV = "SOURCE_RELAY_CID";

    /** Comma-separated {@code host=vsockPort} pairs (sources env SOURCE_RELAY_PORT_MAP). */
    static final String RELAY_PORT_MAP_ENV = "SOURCE_RELAY_PORT_MAP";

    /** Standard Nitro parent CID (VMADDR_CID_HOST). */
    static final int DEFAULT_RELAY_CID = 3;

    private final int relayCid;
    private final Map<String, Integer> hostToVsockPort;

    /** Production transport built from the real process environment. */
    public AfVsockSourceTransport() {
        this(fromEnv(System.getenv()));
    }

    /** Internal copy-constructor used by {@link #fromEnv(Map)}. */
    private AfVsockSourceTransport(AfVsockSourceTransport config) {
        this(config.relayCid, config.hostToVsockPort);
    }

    /** Transport with an explicit relay CID and host-&gt;vsock-port map (offline-testable). */
    AfVsockSourceTransport(int relayCid, Map<String, Integer> hostToVsockPort) {
        this.relayCid = relayCid;
        this.hostToVsockPort = Map.copyOf(hostToVsockPort);
    }

    /**
     * Build a transport from an injected env map: {@code SOURCE_RELAY_CID}
     * (default {@link #DEFAULT_RELAY_CID}) and {@code SOURCE_RELAY_PORT_MAP}
     * (default empty). Package-private so unit tests stay fully offline.
     */
    static AfVsockSourceTransport fromEnv(Map<String, String> env) {
        int cid = DEFAULT_RELAY_CID;
        String rawCid = env.get(RELAY_CID_ENV);
        if (rawCid != null && !rawCid.trim().isEmpty()) {
            cid = Integer.parseInt(rawCid.trim());
        }
        Map<String, Integer> map = parsePortMap(env.get(RELAY_PORT_MAP_ENV));
        return new AfVsockSourceTransport(cid, map);
    }

    /**
     * Parse {@code SOURCE_RELAY_PORT_MAP}: comma-separated {@code host=vsockPort}
     * pairs. Each pair splits on the FIRST {@code =}; host and port are trimmed;
     * empty entries (trailing/double commas) are skipped; the port is parsed with
     * {@link Integer#parseInt}. Null/blank input yields an empty map.
     */
    static Map<String, Integer> parsePortMap(String raw) {
        Map<String, Integer> map = new HashMap<>();
        if (raw == null || raw.trim().isEmpty()) {
            return map;
        }
        for (String pair : raw.split(",")) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("RELAY_PORT_MAP_INVALID");
            }
            String host = trimmed.substring(0, eq).trim();
            String port = trimmed.substring(eq + 1).trim();
            if (host.isEmpty() || port.isEmpty()) {
                throw new IllegalArgumentException("RELAY_PORT_MAP_INVALID");
            }
            map.put(host, Integer.parseInt(port));
        }
        return map;
    }

    /**
     * Resolve the vsock port the relay listens on for {@code host}. Fail-closed:
     * an unmapped host throws {@code RELAY_PORT_UNMAPPED} (NO TCP fallback).
     * Package-private so the mapping logic is unit-testable without a real vsock.
     */
    int resolveVsockPort(String host) {
        Integer vsockPort = hostToVsockPort.get(host);
        if (vsockPort == null) {
            throw new IllegalArgumentException("RELAY_PORT_UNMAPPED");
        }
        return vsockPort;
    }

    /**
     * Open the base socket for {@code host}. The {@code port} (443) is reached by
     * the parent's vsock-proxy, so the vsock dial targets {@code (relayCid,
     * resolveVsockPort(host))}; the returned {@link AFVSOCKSocket} extends
     * {@link Socket}, so the TLS client wraps it as usual and still verifies the
     * certificate against {@code host}.
     */
    @Override
    public Socket connect(String host, int port) throws IOException {
        int vsockPort = resolveVsockPort(host);
        return AFVSOCKSocket.connectTo(AFVSOCKSocketAddress.ofPortAndCID(vsockPort, relayCid));
    }
}
