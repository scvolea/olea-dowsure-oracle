package com.olea.dowsure.coordinator;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Byte-exact checks of {@link Canonicalizer} against the Python {@code canonicalize}
 * algorithm. Expected strings are what the Python implementation produces.
 */
class CanonicalizerTest {

    @Test
    void scalarsMatchCompactJson() {
        assertEquals("null", Canonicalizer.canonicalize(null));
        assertEquals("true", Canonicalizer.canonicalize(true));
        assertEquals("false", Canonicalizer.canonicalize(false));
        assertEquals("42", Canonicalizer.canonicalize(42));
        assertEquals("\"hello\"", Canonicalizer.canonicalize("hello"));
    }

    @Test
    void utf8StringsStayUnescaped() {
        // ensure_ascii=False in Python keeps the raw UTF-8 character.
        assertEquals("\"caf\u00e9\"", Canonicalizer.canonicalize("caf\u00e9"));
    }

    @Test
    void arraysUseCompactSeparators() {
        assertEquals("[1,2,3]", Canonicalizer.canonicalize(List.of(1, 2, 3)));
        assertEquals("[\"a\",\"b\"]", Canonicalizer.canonicalize(List.of("a", "b")));
    }

    @Test
    void objectKeysAreSortedWithCompactSeparators() {
        // Insertion order is deliberately NOT sorted to prove the canonicalizer sorts.
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("b", 2);
        map.put("a", 1);
        assertEquals("{\"a\":1,\"b\":2}", Canonicalizer.canonicalize(map));
    }

    @Test
    void nestedObjectsAndArraysSortRecursively() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("z", "last");
        inner.put("a", "first");
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("items", List.of(inner));
        outer.put("count", 1);
        assertEquals("{\"count\":1,\"items\":[{\"a\":\"first\",\"z\":\"last\"}]}",
                Canonicalizer.canonicalize(outer));
    }

    @Test
    void rejectsNonCanonicalValue() {
        assertThrows(IllegalArgumentException.class, () -> Canonicalizer.canonicalize(new Object()));
    }
}
