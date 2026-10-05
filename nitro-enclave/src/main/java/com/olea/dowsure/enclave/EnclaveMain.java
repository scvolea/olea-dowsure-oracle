package com.olea.dowsure.enclave;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public final class EnclaveMain {
    /** CA bundle (PEM) trusted for source TLS; path or classpath resource. */
    private static final String CA_BUNDLE = System.getenv().getOrDefault("SOURCE_CA_BUNDLE", "source-ca-bundle.pem");

    private EnclaveMain() {
    }

    public static void main(String[] args) {
        ObjectMapper mapper = new ObjectMapper();
        SourceRegistry sourceRegistry = new SourceRegistry();
        // Production transport: vsock->TCP binding handed back as a base Socket for the TLS client to wrap.
        SourceTransport transport = new SourceTransport.TcpSourceTransport();
        SourceTlsClient sourceTlsClient = new SourceTlsClient(transport, CA_BUNDLE);
        CredentialProvider credentialProvider = new PocCredentialProvider();
        EnclaveService service = new EnclaveService(mapper, new JnaAttestationProvider(),
                sourceTlsClient, credentialProvider, sourceRegistry);
        try {
            new VsockServer().serve(16, 5005, request -> {
            try {
                Map<String, Object> input = mapper.readValue(request, new TypeReference<>() {
                });
                return mapper.writeValueAsString(Map.of("ok", true, "evidence", service.acquire(input)));
            } catch (Exception error) {
                return mapper.createObjectNode().put("ok", false).put("error", error.getMessage()).toString();
            }
            });
        } catch (Exception error) {
            throw new IllegalStateException("VSOCK_SERVER_FAILED", error);
        }
    }
}
