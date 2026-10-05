package com.olea.dowsure.enclave;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sandbox/prod credential provider SEAM/skeleton. Builds the provider auth header
 * per {@code sources.py} {@code build_auth_headers}:
 * <ul>
 *   <li>amazon: {@code x-amz-access-token} (value from an LWA exchange hook)</li>
 *   <li>alicloud: {@code Authorization: APPCODE <code>}</li>
 *   <li>qichacha: {@code key}/{@code Timespan}/{@code Token} via {@link SourceRegistry#qichachaToken}</li>
 *   <li>gutu: {@code Authorization: Bearer <token>}</li>
 * </ul>
 *
 * <p>In production the credential ciphertext is decrypted via KMS gated on the
 * enclave attestation document. That decrypt is intentionally a documented HOOK:
 * this skeleton reads already-resolved secret material from the injected env map
 * (standing in for the attested-KMS output) and throws
 * {@code SOURCE_AUTH_FAILED} when a required credential is absent. No secret value
 * is ever logged.
 */
public final class KmsAttestedCredentialProvider implements CredentialProvider {

    private final Map<String, String> env;

    public KmsAttestedCredentialProvider() {
        this(System.getenv());
    }

    public KmsAttestedCredentialProvider(Map<String, String> env) {
        this.env = env;
    }

    @Override
    public Map<String, String> headersFor(SourceEntry entry, Map<String, Object> requestContext) {
        String provider = entry.provider();
        switch (provider) {
            case "amazon":
                // HOOK: production exchanges the LWA refresh token for an access token
                // (sources.py build_amazon_access_token). Here the attested-KMS output is
                // represented by AMAZON_ACCESS_TOKEN; a SigV4 region hook mirrors sources.py.
                String accessToken = require("AMAZON_ACCESS_TOKEN");
                Map<String, String> amazon = new LinkedHashMap<>();
                amazon.put("x-amz-access-token", accessToken);
                String region = env.get("AWS_REGION");
                if (region != null && !region.isEmpty()) {
                    amazon.put("x-amz-sigv4-region", region);
                }
                return amazon;
            case "alicloud":
                return Map.of("Authorization", "APPCODE " + require("ALICLOUD_APPCODE"));
            case "qichacha":
                String appKey = require("QICHACHA_APP_KEY");
                String secretKey = require("QICHACHA_SECRET_KEY");
                String timespan = resolveTimespan(requestContext);
                Map<String, String> qichacha = new LinkedHashMap<>();
                qichacha.put("key", appKey);
                qichacha.put("Timespan", timespan);
                qichacha.put("Token", SourceRegistry.qichachaToken(appKey, timespan, secretKey));
                return qichacha;
            case "gutu":
                return Map.of("Authorization", "Bearer " + require("GUTU_TOKEN"));
            default:
                throw new IllegalArgumentException("SOURCE_AUTH_FAILED");
        }
    }

    private String resolveTimespan(Map<String, Object> requestContext) {
        if (requestContext != null) {
            Object fromContext = requestContext.get("timespan");
            if (fromContext instanceof String value && !value.isEmpty()) {
                return value;
            }
        }
        return env.getOrDefault("QICHACHA_TIMESPAN", "");
    }

    private String require(String envVar) {
        String value = env.get(envVar);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("SOURCE_AUTH_FAILED");
        }
        return value;
    }
}
