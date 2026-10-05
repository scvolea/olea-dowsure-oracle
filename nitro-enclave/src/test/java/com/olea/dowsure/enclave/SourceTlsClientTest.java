package com.olea.dowsure.enclave;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceTlsClientTest {

    private static final String KEYSTORE = "test-server.p12";
    private static final String CA_PEM = "test-ca.pem";
    private static final String PASS = "changeit";
    private static final byte[] STUB_RESPONSE =
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".getBytes(StandardCharsets.UTF_8);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SSLServerSocket serverSocket;

    @AfterEach
    void tearDown() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
        executor.shutdownNow();
    }

    @Test
    void returnsFullResponseBytesAndShapesRequest() throws Exception {
        CompletableFuture<byte[]> captured = startStub();
        int port = serverSocket.getLocalPort();

        SourceEntry entry = new SourceEntry("getOrderMetrics", "amazon", "localhost", port,
                "GET", "/sales/v1/orderMetrics", Map.of("marketplaceIds", "ATVPDKIKX0DER"),
                "amazon", "application/json");

        SourceTlsClient client = new SourceTlsClient(new LocalTransport(), trustStoreFromPem());
        byte[] response = client.fetch(entry, null, Map.of("x-api-key", "k-1"));

        assertEquals(new String(STUB_RESPONSE, StandardCharsets.UTF_8), new String(response, StandardCharsets.UTF_8));

        String request = new String(captured.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
        assertTrue(request.startsWith("GET /sales/v1/orderMetrics?marketplaceIds=ATVPDKIKX0DER HTTP/1.1\r\n"),
                "request line should carry method, path, encoded params");
        assertTrue(request.contains("\r\nHost: localhost\r\n"), "Host header");
        assertTrue(request.contains("\r\nConnection: close\r\n"), "Connection: close header");
        assertTrue(request.contains("\r\nAccept-Encoding: identity\r\n"), "Accept-Encoding: identity header");
        assertTrue(request.contains("\r\nx-api-key: k-1\r\n"), "auth header attached");
    }

    @Test
    void postShapesContentTypeAndLength() throws Exception {
        CompletableFuture<byte[]> captured = startStub();
        int port = serverSocket.getLocalPort();

        SourceEntry entry = new SourceEntry("gutuPanoramaChecks", "gutu", "localhost", port,
                "POST", "/api/v1/judicial/panorama-checks", Map.of(), "gutu", "application/json");

        SourceTlsClient client = new SourceTlsClient(new LocalTransport(), trustStoreFromPem());
        client.fetch(entry, Map.of("q", "x"), Map.of());

        String request = new String(captured.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
        assertTrue(request.startsWith("POST /api/v1/judicial/panorama-checks HTTP/1.1\r\n"), "POST request line");
        assertTrue(request.contains("\r\nContent-Type: application/json\r\n"), "Content-Type header");
        assertTrue(request.contains("\r\nContent-Length: 9\r\n"), "Content-Length for {\"q\":\"x\"}");
        assertTrue(request.endsWith("{\"q\":\"x\"}"), "JSON body appended");
    }

    @Test
    void untrustedChainThrowsTlsHandshakeFailed() throws Exception {
        startStub();
        int port = serverSocket.getLocalPort();

        SourceEntry entry = new SourceEntry("getOrderMetrics", "amazon", "localhost", port,
                "GET", "/sales/v1/orderMetrics", Map.of(), "amazon", "application/json");

        // Empty trust store -> the stub's self-signed cert is not trusted.
        KeyStore emptyTrust = KeyStore.getInstance(KeyStore.getDefaultType());
        emptyTrust.load(null, null);
        SourceTlsClient client = new SourceTlsClient(new LocalTransport(), emptyTrust);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> client.fetch(entry, null, Map.of()));
        assertEquals("TLS_HANDSHAKE_FAILED", error.getMessage());
    }

    /** Opens a plain TCP socket to the local stub (the TLS client wraps it). */
    private static final class LocalTransport implements SourceTransport {
        @Override
        public Socket connect(String host, int port) throws IOException {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port));
            return socket;
        }
    }

    /** Start a one-shot TLS stub; returns a future with the raw request bytes it read. */
    private CompletableFuture<byte[]> startStub() throws Exception {
        SSLServerSocketFactory factory = serverContext().getServerSocketFactory();
        serverSocket = (SSLServerSocket) factory.createServerSocket();
        serverSocket.bind(new InetSocketAddress("localhost", 0));

        CompletableFuture<byte[]> captured = new CompletableFuture<>();
        executor.submit(() -> {
            try (SSLSocket accepted = (SSLSocket) serverSocket.accept()) {
                InputStream in = accepted.getInputStream();
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                // Read until the end of the HTTP head (and any POST body already buffered).
                int read;
                while ((read = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                    if (containsHeadEnd(buffer.toByteArray()) && !expectsMoreBody(buffer.toByteArray())) {
                        break;
                    }
                }
                captured.complete(buffer.toByteArray());
                OutputStream out = accepted.getOutputStream();
                out.write(STUB_RESPONSE);
                out.flush();
            } catch (Throwable error) {
                captured.completeExceptionally(error);
            }
        });
        return captured;
    }

    private static boolean containsHeadEnd(byte[] data) {
        return new String(data, StandardCharsets.UTF_8).contains("\r\n\r\n");
    }

    private static boolean expectsMoreBody(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        int headEnd = text.indexOf("\r\n\r\n");
        if (headEnd < 0) {
            return true;
        }
        int contentLength = parseContentLength(text.substring(0, headEnd));
        int bodyBytes = data.length - (headEnd + 4);
        return bodyBytes < contentLength;
    }

    private static int parseContentLength(String head) {
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                return Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        return 0;
    }

    private static SSLContext serverContext() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = resource(KEYSTORE)) {
            keyStore.load(in, PASS.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, PASS.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, null);
        return context;
    }

    private static KeyStore trustStoreFromPem() throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        try (InputStream in = resource(CA_PEM)) {
            int index = 0;
            for (Certificate certificate : cf.generateCertificates(in)) {
                trustStore.setCertificateEntry("ca-" + index++, certificate);
            }
        }
        return trustStore;
    }

    private static InputStream resource(String name) {
        InputStream in = SourceTlsClientTest.class.getClassLoader().getResourceAsStream(name);
        if (in == null) {
            throw new IllegalStateException("test resource missing: " + name);
        }
        return in;
    }
}
