package com.olea.dowsure.enclave;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Transport seam for {@link SourceTlsClient}: hands back a base {@link Socket}
 * to {@code host:port} which the TLS client then wraps in an {@code SSLSocket}.
 *
 * <p>Mirrors the coordinator's {@code VsockChannel}/{@code AfVsockTransport}
 * seam so the connect step is injectable and the TLS client can be unit-tested
 * against a local in-process TLS stub. In production {@code EnclaveMain}
 * (FEAT-002) swaps in a vsock-&gt;TCP transport; here the default opens a plain
 * TCP socket.
 */
public interface SourceTransport {

    /** Open a base socket to {@code host:port}. */
    Socket connect(String host, int port) throws IOException;

    /** Default production transport: a plain TCP {@link Socket}. */
    final class TcpSourceTransport implements SourceTransport {
        @Override
        public Socket connect(String host, int port) throws IOException {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port));
            return socket;
        }
    }
}
