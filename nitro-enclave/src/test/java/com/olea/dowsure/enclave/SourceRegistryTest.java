package com.olea.dowsure.enclave;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SourceRegistryTest {

    private SourceRegistry registry(Map<String, String> env) {
        return new SourceRegistry(env);
    }

    @Test
    void resolvesAllSevenIdsToCorrectProviderMethodAndPath() {
        SourceRegistry registry = registry(Map.of());

        assertEntry(registry.resolve("getOrderMetrics"), "amazon", "GET", "/sales/v1/orderMetrics");
        assertEntry(registry.resolve("listFinancialEventGroups"), "amazon", "GET", "/finances/v0/financialEventGroups");
        assertEntry(registry.resolve("listTransactions"), "amazon", "GET", "/finances/2024-06-19/transactions");
        assertEntry(registry.resolve("alicloudTelThree"), "alicloud", "GET", "/lundear/telThree");
        assertEntry(registry.resolve("qichachaEnterpriseVerify"), "qichacha", "GET", "/EnterpriseInfo/Verify");
        assertEntry(registry.resolve("qichachaShixinCheck"), "qichacha", "GET", "/ShixinCheck/GetList");
        assertEntry(registry.resolve("gutuPanoramaChecks"), "gutu", "POST", "/api/v1/judicial/panorama-checks");
    }

    @Test
    void unknownIdThrowsSourceScopeInvalid() {
        SourceRegistry registry = registry(Map.of());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> registry.resolve("nope"));
        assertEquals("SOURCE_SCOPE_INVALID", error.getMessage());
    }

    @Test
    void qichachaTokenMatchesVerifiedVector() {
        assertEquals("a944de67cf3bfb9eca4c83db310fda07",
                SourceRegistry.qichachaToken("appkey123", "20240101120000", "secret456"));
    }

    @Test
    void mockModeFallsBackToMockDefaultHost() {
        SourceRegistry registry = registry(Map.of());
        // SOURCE_MODE unset -> mock -> MOCK_BASE_URL_DEFAULT http://localhost:4010
        SourceEntry entry = registry.resolve("getOrderMetrics");
        assertEquals("localhost", entry.host());
        assertEquals(4010, entry.port());
    }

    @Test
    void perProviderEnvOverrideWins() {
        SourceRegistry registry = registry(Map.of("AMAZON_SP_BASE_URL", "https://override.example.com:8443/"));
        SourceEntry entry = registry.resolve("getOrderMetrics");
        assertEquals("override.example.com", entry.host());
        assertEquals(8443, entry.port());
    }

    @Test
    void sandboxModeUsesSandboxBaseUrlHost() {
        SourceRegistry registry = registry(Map.of("SOURCE_MODE", "sandbox"));
        SourceEntry entry = registry.resolve("qichachaEnterpriseVerify");
        assertEquals("api.qichacha.com", entry.host());
        assertEquals(443, entry.port());
    }

    private static void assertEntry(SourceEntry entry, String provider, String method, String path) {
        assertEquals(provider, entry.provider());
        assertEquals(method, entry.method());
        assertEquals(path, entry.pathTemplate());
    }
}
