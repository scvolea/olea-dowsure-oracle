package com.olea.dowsure.coordinator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * HTTP implementation of {@link OleaClient}. Mirrors the Python coordinator's
 * {@code post}: JSON body, {@code Content-Type: application/json}, 30s timeout.
 *
 * <p>This is the only class that performs real network I/O. It never logs the
 * request body or response (which may carry a challenge/nonce).
 */
public final class HttpOleaClient implements OleaClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public HttpOleaClient(ObjectMapper mapper) {
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    @Override
    public Map<String, Object> post(String url, Map<String, Object> body) {
        try {
            byte[] payload = mapper.writeValueAsBytes(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return mapper.readValue(new String(response.body(), StandardCharsets.UTF_8), new TypeReference<Map<String, Object>>() {
            });
        } catch (java.io.IOException | InterruptedException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("OLEA_REQUEST_FAILED", error);
        }
    }
}
