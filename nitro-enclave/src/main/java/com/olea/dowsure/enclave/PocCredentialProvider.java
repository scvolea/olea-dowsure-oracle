package com.olea.dowsure.enclave;

import java.util.Map;

/**
 * PoC/mock-mode credential provider: zero provider secrets. Mirrors
 * {@code sources.py} mock-mode {@code build_auth_headers} (returns {@code {}})
 * plus {@code _mock_api_key_headers} (attaches {@code x-api-key} only when
 * {@code MOCK_API_KEY} is present for the auth-gated deployed mock).
 *
 * <p>Env is read through an injectable map (default {@link System#getenv()}) so
 * tests are offline. No secret value is logged.
 */
public final class PocCredentialProvider implements CredentialProvider {

    private final Map<String, String> env;

    public PocCredentialProvider() {
        this(System.getenv());
    }

    public PocCredentialProvider(Map<String, String> env) {
        this.env = env;
    }

    @Override
    public Map<String, String> headersFor(SourceEntry entry, Map<String, Object> requestContext) {
        String apiKey = env.get("MOCK_API_KEY");
        if (apiKey != null && !apiKey.isEmpty()) {
            return Map.of("x-api-key", apiKey);
        }
        return Map.of();
    }
}
