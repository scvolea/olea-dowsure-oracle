package com.olea.dowsure.coordinator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Byte-exact port of the Python coordinator's {@code canonicalize} function.
 *
 * <p>Rules (identical to the Python implementation):
 * <ul>
 *   <li>{@code null}, booleans, strings, and numbers are rendered with compact JSON
 *       (separators {@code ,} and {@code :}, non-ASCII left as UTF-8 — matching
 *       Python's {@code json.dumps(value, separators=(",", ":"), ensure_ascii=False)}).</li>
 *   <li>Lists become {@code [} + comma-joined canonical items + {@code ]}.</li>
 *   <li>Maps become {@code {} + comma-joined {@code "key":value} pairs with keys
 *       sorted lexicographically, then {@code }}.</li>
 * </ul>
 *
 * <p>The output is the exact byte sequence the Dowsure submission signature is
 * computed over, so it MUST stay identical to the Python output.
 */
public final class Canonicalizer {
    private static final ObjectMapper SCALAR_MAPPER = new ObjectMapper();

    private Canonicalizer() {
    }

    public static String canonicalize(Object value) {
        if (value == null || value instanceof Boolean || value instanceof String || value instanceof Number) {
            return scalar(value);
        }
        if (value instanceof List<?> list) {
            List<String> items = new ArrayList<>(list.size());
            for (Object item : list) {
                items.add(canonicalize(item));
            }
            return "[" + String.join(",", items) + "]";
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), item));
            List<String> pairs = new ArrayList<>(sorted.size());
            sorted.forEach((key, item) -> pairs.add(scalar(key) + ":" + canonicalize(item)));
            return "{" + String.join(",", pairs) + "}";
        }
        throw new IllegalArgumentException("NON_CANONICAL_VALUE");
    }

    private static String scalar(Object value) {
        try {
            // Jackson writes JSON scalars with no insignificant whitespace and leaves
            // non-ASCII characters un-escaped by default, matching the Python contract.
            return SCALAR_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("NON_CANONICAL_VALUE", error);
        }
    }
}
