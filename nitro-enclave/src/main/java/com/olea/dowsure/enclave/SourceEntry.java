package com.olea.dowsure.enclave;

import java.util.Map;

/**
 * One registry entry describing a single data-source endpoint. Immutable leaf
 * record ported from the per-endpoint selection in {@code coordinator/sources.py}
 * (host/path/method/auth-type). No network I/O lives here.
 *
 * @param id             the sourceId used by callers to resolve this entry
 * @param provider       logical provider key (amazon/alicloud/qichacha/gutu)
 * @param host           resolved host (authority) the TLS client connects to and validates
 * @param port           TLS port (443)
 * @param method         HTTP method (GET/POST)
 * @param pathTemplate   request path, mirroring the path passed in sources.py
 * @param requiredParams default query params for the endpoint (empty in mock mode)
 * @param authType       provider auth scheme key for the credential provider
 * @param expectedFormat response media type expected from the source
 */
public record SourceEntry(
        String id,
        String provider,
        String host,
        int port,
        String method,
        String pathTemplate,
        Map<String, String> requiredParams,
        String authType,
        String expectedFormat) {
}
