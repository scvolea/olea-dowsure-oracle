package com.olea.dowsure.enclave;

import java.util.Map;

/**
 * Supplies the provider auth headers for a {@link SourceEntry} fetch. Implementations
 * decide the header NAME/VALUE per provider; the {@link SourceTlsClient} attaches
 * whatever is returned verbatim. No secret value is ever logged by an implementation.
 */
public interface CredentialProvider {

    /**
     * Build the auth headers for {@code entry}.
     *
     * @param entry          the resolved source endpoint
     * @param requestContext per-call context (e.g. a timespan for qichacha); may be empty
     * @return header name/value pairs to attach (possibly empty)
     */
    Map<String, String> headersFor(SourceEntry entry, Map<String, Object> requestContext);
}
