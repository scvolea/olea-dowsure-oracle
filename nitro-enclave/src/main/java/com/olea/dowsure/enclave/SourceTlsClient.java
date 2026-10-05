package com.olea.dowsure.enclave;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Map;

/**
 * TLS client that performs a single HTTP/1.1 request against a {@link SourceEntry}
 * over a socket supplied by an injected {@link SourceTransport} seam. The chain is
 * validated against a configurable CA bundle and the hostname is checked against
 * {@code entry.host()}; any failure surfaces as {@code TLS_HANDSHAKE_FAILED}.
 *
 * <p>The FULL response bytes (status line + headers + body until connection close)
 * are returned verbatim so the caller can hash the exact wire bytes. Request and
 * response bytes, header values, and the request body are NEVER logged.
 */
public final class SourceTlsClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SourceTransport transport;
    private final SSLContext sslContext;

    /** Build a client trusting the PEM CA bundle at {@code caBundlePath}. */
    public SourceTlsClient(SourceTransport transport, String caBundlePath) {
        this(transport, loadFromPath(caBundlePath));
    }

    /** Build a client with a pre-built trust store (used by tests). */
    public SourceTlsClient(SourceTransport transport, KeyStore trustStore) {
        this.transport = transport;
        this.sslContext = buildContext(trustStore);
    }

    /**
     * Perform the request described by {@code entry} and return the full response bytes.
     *
     * @param entry       the resolved source endpoint
     * @param requestBody POST JSON body (ignored for GET); may be {@code null}
     * @param authHeaders provider auth headers to attach (never logged)
     */
    public byte[] fetch(SourceEntry entry, Object requestBody, Map<String, String> authHeaders) {
        try {
            Socket base = transport.connect(entry.host(), entry.port());
            SSLSocketFactory factory = sslContext.getSocketFactory();
            try (SSLSocket socket = (SSLSocket) factory.createSocket(base, entry.host(), entry.port(), true)) {
                // Validate the hostname against entry.host() during the handshake (HTTPS endpoint-id check).
                SSLParameters params = socket.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(params);
                try {
                    socket.startHandshake();
                } catch (IOException handshakeError) {
                    throw new IllegalArgumentException("TLS_HANDSHAKE_FAILED", handshakeError);
                }
                byte[] request = buildRequest(entry, requestBody, authHeaders);
                OutputStream out = socket.getOutputStream();
                out.write(request);
                out.flush();
                return readAll(socket.getInputStream());
            }
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (IOException error) {
            throw new IllegalArgumentException("TLS_HANDSHAKE_FAILED", error);
        }
    }

    private static byte[] buildRequest(SourceEntry entry, Object requestBody, Map<String, String> authHeaders) {
        boolean post = "POST".equalsIgnoreCase(entry.method());
        String path = entry.pathTemplate();
        byte[] body = new byte[0];
        if (post) {
            try {
                body = MAPPER.writeValueAsBytes(requestBody == null ? Map.of() : requestBody);
            } catch (Exception error) {
                throw new IllegalArgumentException("SOURCE_REQUEST_INVALID", error);
            }
        } else if (!entry.requiredParams().isEmpty()) {
            path = path + "?" + encodeQuery(entry.requiredParams());
        }

        StringBuilder head = new StringBuilder();
        head.append(entry.method()).append(' ').append(path).append(" HTTP/1.1\r\n");
        head.append("Host: ").append(entry.host()).append("\r\n");
        head.append("Connection: close\r\n");
        head.append("Accept-Encoding: identity\r\n");
        if (authHeaders != null) {
            for (Map.Entry<String, String> header : authHeaders.entrySet()) {
                head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
            }
        }
        if (post) {
            head.append("Content-Type: application/json\r\n");
            head.append("Content-Length: ").append(body.length).append("\r\n");
        }
        head.append("\r\n");

        byte[] headBytes = head.toString().getBytes(StandardCharsets.UTF_8);
        if (!post) {
            return headBytes;
        }
        byte[] out = new byte[headBytes.length + body.length];
        System.arraycopy(headBytes, 0, out, 0, headBytes.length);
        System.arraycopy(body, 0, out, headBytes.length, body.length);
        return out;
    }

    private static String encodeQuery(Map<String, String> params) {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> param : params.entrySet()) {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(URLEncoder.encode(param.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(param.getValue(), StandardCharsets.UTF_8));
        }
        return query.toString();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static SSLContext buildContext(KeyStore trustStore) {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, tmf.getTrustManagers(), null);
            return context;
        } catch (Exception error) {
            throw new IllegalStateException("TLS_TRUST_INIT_FAILED", error);
        }
    }

    private static KeyStore loadFromPath(String caBundlePath) {
        try (InputStream in = openBundle(caBundlePath)) {
            if (in == null) {
                throw new IllegalStateException("CA_BUNDLE_NOT_FOUND");
            }
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            int index = 0;
            for (Certificate certificate : cf.generateCertificates(in)) {
                trustStore.setCertificateEntry("ca-" + index++, certificate);
            }
            return trustStore;
        } catch (Exception error) {
            throw new IllegalStateException("TLS_TRUST_INIT_FAILED", error);
        }
    }

    private static InputStream openBundle(String caBundlePath) throws IOException {
        java.io.File file = new java.io.File(caBundlePath);
        if (file.isFile()) {
            return new java.io.FileInputStream(file);
        }
        return SourceTlsClient.class.getClassLoader().getResourceAsStream(caBundlePath);
    }
}
