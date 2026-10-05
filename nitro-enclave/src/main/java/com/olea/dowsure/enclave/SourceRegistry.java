package com.olea.dowsure.enclave;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The 7-entry source registry. Behaviour (mode switch, base-URL precedence,
 * per-provider host/path/method/auth, and the qichacha token) is ported
 * VERBATIM from {@code coordinator/sources.py} so the Java enclave resolves
 * each endpoint identically to the Python reference.
 *
 * <p>All environment reads go through an injectable {@code Map<String,String>}
 * (default {@link System#getenv()}) so unit tests are fully offline. No network
 * I/O and no secrets live in this class.
 */
public final class SourceRegistry {

    static final String MODE_MOCK = "mock";
    static final String MODE_SANDBOX = "sandbox";

    /** Real provider hosts used as the sandbox-mode base URL defaults (sources.py SANDBOX_BASE_URLS). */
    private static final Map<String, String> SANDBOX_BASE_URLS = Map.of(
            "amazon", "https://sandbox.sellingpartnerapi-na.amazon.com",
            "alicloud", "https://qrymobile.market.alicloudapi.com",
            "qichacha", "https://api.qichacha.com",
            "gutu", "https://turningapi.valuemap.cn");

    /** Harmless local default for mock mode (sources.py MOCK_BASE_URL_DEFAULT). */
    static final String MOCK_BASE_URL_DEFAULT = "http://localhost:4010";

    /** Env var holding the per-provider base URL override (sources.py PROVIDER_ENV). */
    private static final Map<String, String> PROVIDER_ENV = Map.of(
            "amazon", "AMAZON_SP_BASE_URL",
            "alicloud", "ALICLOUD_BASE_URL",
            "qichacha", "QICHACHA_BASE_URL",
            "gutu", "GUTU_BASE_URL");

    /** Static endpoint definitions: id -> {provider, method, pathTemplate}. Order mirrors the 7 calls. */
    private record Definition(String provider, String method, String pathTemplate) {
    }

    private static final Map<String, Definition> DEFINITIONS = buildDefinitions();

    private static Map<String, Definition> buildDefinitions() {
        Map<String, Definition> defs = new LinkedHashMap<>();
        defs.put("getOrderMetrics", new Definition("amazon", "GET", "/sales/v1/orderMetrics"));
        defs.put("listFinancialEventGroups", new Definition("amazon", "GET", "/finances/v0/financialEventGroups"));
        defs.put("listTransactions", new Definition("amazon", "GET", "/finances/2024-06-19/transactions"));
        defs.put("alicloudTelThree", new Definition("alicloud", "GET", "/lundear/telThree"));
        defs.put("qichachaEnterpriseVerify", new Definition("qichacha", "GET", "/EnterpriseInfo/Verify"));
        defs.put("qichachaShixinCheck", new Definition("qichacha", "GET", "/ShixinCheck/GetList"));
        defs.put("gutuPanoramaChecks", new Definition("gutu", "POST", "/api/v1/judicial/panorama-checks"));
        return defs;
    }

    private final Map<String, String> env;

    /** Default registry reading the real process environment. */
    public SourceRegistry() {
        this(System.getenv());
    }

    /** Registry reading an injected env map (offline-testable). */
    public SourceRegistry(Map<String, String> env) {
        this.env = env;
    }

    /** Active mode: any SOURCE_MODE value other than 'sandbox' means mock (sources.py source_mode). */
    public String mode() {
        return MODE_SANDBOX.equals(env.getOrDefault("SOURCE_MODE", MODE_MOCK)) ? MODE_SANDBOX : MODE_MOCK;
    }

    /**
     * Select the base URL for a provider given the mode (sources.py resolve_base_url).
     * Precedence: explicit per-provider env override > mode default. In mock mode the
     * default is a harmless local stub URL and NO credential is required.
     */
    public String resolveBaseUrl(String provider, String mode) {
        String override = env.get(PROVIDER_ENV.get(provider));
        if (override != null && !override.isEmpty()) {
            return stripTrailingSlash(override);
        }
        if (MODE_SANDBOX.equals(mode)) {
            return SANDBOX_BASE_URLS.get(provider);
        }
        return MOCK_BASE_URL_DEFAULT;
    }

    /**
     * Resolve a sourceId into a fully-populated {@link SourceEntry}. The host/port
     * are derived from the resolved base URL for the entry's provider.
     *
     * @throws IllegalArgumentException with message {@code SOURCE_SCOPE_INVALID} for an unknown id
     */
    public SourceEntry resolve(String sourceId) {
        Definition def = DEFINITIONS.get(sourceId);
        if (def == null) {
            throw new IllegalArgumentException("SOURCE_SCOPE_INVALID");
        }
        String mode = mode();
        String baseUrl = resolveBaseUrl(def.provider(), mode);
        URI uri = URI.create(baseUrl);
        String host = uri.getHost();
        int port = uri.getPort() != -1 ? uri.getPort() : defaultPort(uri.getScheme());
        return new SourceEntry(
                sourceId,
                def.provider(),
                host,
                port,
                def.method(),
                def.pathTemplate(),
                Map.of(),
                def.provider(),
                "application/json");
    }

    private static int defaultPort(String scheme) {
        return "http".equalsIgnoreCase(scheme) ? 80 : 443;
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /** Qichacha signing: md5(appKey + timespan + secretKey), lowercase hex (sources.py qichacha_token). */
    public static String qichachaToken(String appKey, String timespan, String secretKey) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest((appKey + timespan + secretKey).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
