package com.olea.dowsure.coordinator;

import java.util.Map;

/**
 * Typed adapter for the Olea challenge service. The only abstraction the
 * coordinator uses to reach Olea over the network, so tests can supply a fake
 * without any real HTTP call.
 */
public interface OleaClient {
    /**
     * POSTs the JSON {@code body} to {@code url} and returns the parsed JSON response.
     */
    Map<String, Object> post(String url, Map<String, Object> body);
}
