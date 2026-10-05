package com.olea.dowsure.enclave;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialProviderTest {

    private static SourceEntry entry(String provider) {
        return new SourceEntry("id", provider, "host", 443, "GET", "/p", Map.of(), provider, "application/json");
    }

    @Test
    void pocReturnsEmptyWithoutMockApiKey() {
        PocCredentialProvider provider = new PocCredentialProvider(Map.of());
        assertTrue(provider.headersFor(entry("amazon"), Map.of()).isEmpty());
    }

    @Test
    void pocReturnsApiKeyWhenMockApiKeySet() {
        PocCredentialProvider provider = new PocCredentialProvider(Map.of("MOCK_API_KEY", "k-123"));
        Map<String, String> headers = provider.headersFor(entry("amazon"), Map.of());
        assertEquals("k-123", headers.get("x-api-key"));
        assertEquals(1, headers.size());
    }

    @Test
    void sandboxAmazonUsesAccessTokenHeaderName() {
        KmsAttestedCredentialProvider provider = new KmsAttestedCredentialProvider(Map.of("AMAZON_ACCESS_TOKEN", "atk"));
        Map<String, String> headers = provider.headersFor(entry("amazon"), Map.of());
        assertEquals("atk", headers.get("x-amz-access-token"));
        assertFalse(headers.containsKey("x-amz-sigv4-region"));
    }

    @Test
    void sandboxAlicloudUsesAppcodeAuthorization() {
        KmsAttestedCredentialProvider provider = new KmsAttestedCredentialProvider(Map.of("ALICLOUD_APPCODE", "code1"));
        assertEquals("APPCODE code1", provider.headersFor(entry("alicloud"), Map.of()).get("Authorization"));
    }

    @Test
    void sandboxQichachaUsesKeyTimespanToken() {
        KmsAttestedCredentialProvider provider = new KmsAttestedCredentialProvider(Map.of(
                "QICHACHA_APP_KEY", "appkey123",
                "QICHACHA_SECRET_KEY", "secret456"));
        Map<String, String> headers = provider.headersFor(entry("qichacha"), Map.of("timespan", "20240101120000"));
        assertEquals("appkey123", headers.get("key"));
        assertEquals("20240101120000", headers.get("Timespan"));
        assertEquals("a944de67cf3bfb9eca4c83db310fda07", headers.get("Token"));
    }

    @Test
    void sandboxGutuUsesBearerAuthorization() {
        KmsAttestedCredentialProvider provider = new KmsAttestedCredentialProvider(Map.of("GUTU_TOKEN", "tok"));
        assertEquals("Bearer tok", provider.headersFor(entry("gutu"), Map.of()).get("Authorization"));
    }

    @Test
    void missingRequiredCredentialYieldsSourceAuthFailed() {
        KmsAttestedCredentialProvider provider = new KmsAttestedCredentialProvider(Map.of());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> provider.headersFor(entry("gutu"), Map.of()));
        assertEquals("SOURCE_AUTH_FAILED", error.getMessage());
    }
}
