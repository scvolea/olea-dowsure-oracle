package com.olea.dowsure.enclave;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnclaveServiceTest {

    private static final String KEYSTORE = "test-server.p12";
    private static final String CA_PEM = "test-ca.pem";
    private static final String PASS = "changeit";
    private static final String BODY = "{\"payload\":{\"Orders\":[{\"orderId\":\"123\"}]}}";
    private static final byte[] STUB_RESPONSE =
            ("HTTP/1.1 200 OK\r\nContent-Length: " + BODY.getBytes(StandardCharsets.UTF_8).length
                    + "\r\nConnection: close\r\n\r\n" + BODY).getBytes(StandardCharsets.UTF_8);

    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SSLServerSocket serverSocket;

    @AfterEach
    void tearDown() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
        executor.shutdownNow();
    }

    private EnclaveService service(AttestationProvider attestationProvider) throws Exception {
        // Point the amazon base URL at the in-process TLS stub so resolve() yields host:port of the stub.
        int port = serverSocket.getLocalPort();
        Map<String, String> env = Map.of("AMAZON_SP_BASE_URL", "https://localhost:" + port);
        SourceRegistry registry = new SourceRegistry(env);
        SourceTlsClient client = new SourceTlsClient(new LocalTransport(), trustStoreFromPem());
        return new EnclaveService(mapper, attestationProvider, client, new PocCredentialProvider(Map.of()), registry);
    }

    private static Map<String, Object> request(String sourceId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("requestId", "r1");
        request.put("nonce", "n1");
        request.put("policyVersion", "v1");
        request.put("evidenceId", "e1");
        request.put("eifDigest", "eif");
        request.put("sourceId", sourceId);
        return request;
    }

    @Test
    void fetchesOverTlsAndProducesManifestWithoutNotaryFields() throws Exception {
        startStub();
        EnclaveService service = service((userData, publicKey) -> "attestation".getBytes(StandardCharsets.UTF_8));

        Map<String, Object> evidence = service.acquire(request("getOrderMetrics"));

        String rawHash = service.sha256Bytes(STUB_RESPONSE);
        assertEquals(rawHash, evidence.get("rawPayloadDigest"));
        assertEquals(Base64.getEncoder().encodeToString(STUB_RESPONSE), evidence.get("rawResponseB64"));

        // Pass-through: transformedPayload equals the parsed response body AS-IS.
        Map<String, Object> expectedBody = mapper.readValue(BODY, new TypeReference<Map<String, Object>>() {});
        assertEquals(expectedBody, evidence.get("transformedPayload"));
        assertEquals(expectedBody, evidence.get("rawPayload"));

        // No notary fields survive anywhere in evidence.
        assertFalse(evidence.containsKey("tlsProofType"));
        assertFalse(evidence.containsKey("tlsProofHash"));
        assertFalse(evidence.containsKey("tlsProofResponseHash"));
        assertFalse(evidence.containsKey("source"));
        assertFalse(evidence.containsKey("endpoint"));

        assertEquals("attestation", new String(Base64.getUrlDecoder().decode((String) evidence.get("attestationDocument")), StandardCharsets.UTF_8));
    }

    @Test
    void bindsSourceIdAndNonceIntoAttestationUserData() throws Exception {
        startStub();
        AtomicReference<byte[]> capturedUserData = new AtomicReference<>();
        EnclaveService service = service((userData, publicKey) -> {
            capturedUserData.set(userData);
            return "attestation".getBytes(StandardCharsets.UTF_8);
        });

        service.acquire(request("getOrderMetrics"));

        Map<String, Object> userData = mapper.readValue(
                new String(capturedUserData.get(), StandardCharsets.UTF_8), new TypeReference<Map<String, Object>>() {});
        assertEquals("n1", userData.get("nonce"));
        assertEquals("getOrderMetrics", userData.get("sourceId"));
        assertFalse(userData.containsKey("tlsProofHash"));
    }

    @Test
    void unknownSourceIdThrowsScopeInvalid() throws Exception {
        startStub();
        EnclaveService service = service((userData, publicKey) -> "attestation".getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.acquire(request("unknownSource")));
        assertEquals("SOURCE_SCOPE_INVALID", error.getMessage());
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

    /** Start a one-shot TLS stub that returns STUB_RESPONSE. */
    private void startStub() throws Exception {
        SSLServerSocketFactory factory = serverContext().getServerSocketFactory();
        serverSocket = (SSLServerSocket) factory.createServerSocket();
        serverSocket.bind(new InetSocketAddress("localhost", 0));

        executor.submit(() -> {
            try (SSLSocket accepted = (SSLSocket) serverSocket.accept()) {
                InputStream in = accepted.getInputStream();
                byte[] chunk = new byte[4096];
                in.read(chunk); // drain the request head; response is fixed
                OutputStream out = accepted.getOutputStream();
                out.write(STUB_RESPONSE);
                out.flush();
            } catch (Throwable ignored) {
                // stub errors surface as a fetch failure in the test
            }
        });
    }

    private static SSLContext serverContext() throws Exception {
        java.security.KeyStore keyStore = java.security.KeyStore.getInstance("PKCS12");
        try (InputStream in = resource(KEYSTORE)) {
            keyStore.load(in, PASS.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, PASS.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, null);
        return context;
    }

    private static java.security.KeyStore trustStoreFromPem() throws Exception {
        java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
        java.security.KeyStore trustStore = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
        trustStore.load(null, null);
        try (InputStream in = resource(CA_PEM)) {
            int index = 0;
            for (java.security.cert.Certificate certificate : cf.generateCertificates(in)) {
                trustStore.setCertificateEntry("ca-" + index++, certificate);
            }
        }
        return trustStore;
    }

    private static InputStream resource(String name) {
        InputStream in = EnclaveServiceTest.class.getClassLoader().getResourceAsStream(name);
        if (in == null) {
            throw new IllegalStateException("test resource missing: " + name);
        }
        return in;
    }
}
