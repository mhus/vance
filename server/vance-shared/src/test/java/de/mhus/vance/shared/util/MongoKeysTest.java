package de.mhus.vance.shared.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Dot-key sanitization for Mongo. A dot in a map key is a path separator in
 * Mongo, so user-/LLM-chosen keys must be escaped before persist — otherwise
 * the write breaks or nests unexpectedly.
 */
class MongoKeysTest {

    @Test
    void sanitizeKey_replacesDotsWithUnderscore() {
        assertThat(MongoKeys.sanitizeKey("file.txt")).isEqualTo("file_txt");
        assertThat(MongoKeys.sanitizeKey("a.b.c")).isEqualTo("a_b_c");
        assertThat(MongoKeys.sanitizeKey("plain")).isEqualTo("plain");
    }

    @Test
    void sanitize_rewritesKeysRecursivelyThroughMapsAndLists() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("nested.key", 1);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("top.level", inner);
        input.put("list", List.of(mapOf("item.key", "v")));
        input.put("plain", "x");

        Map<String, Object> out = MongoKeys.sanitizeKeys(input);

        assertThat(out).containsOnlyKeys("top_level", "list", "plain");
        assertThat(asMap(out.get("top_level"))).containsOnlyKeys("nested_key");
        assertThat(asMap(asList(out.get("list")).get(0))).containsOnlyKeys("item_key");
        assertThat(out).containsEntry("plain", "x");
    }

    @Test
    void sanitize_leavesInputUntouched() {
        Map<String, Object> input = mapOf("a.b", 1);
        MongoKeys.sanitizeKeys(input);
        assertThat(input).containsOnlyKeys("a.b"); // original not mutated
    }

    @Test
    void sanitize_passesScalarsThrough() {
        assertThat(MongoKeys.sanitize("s")).isEqualTo("s");
        assertThat(MongoKeys.sanitize(42)).isEqualTo(42);
        assertThat(MongoKeys.sanitize(null)).isNull();
    }

    private static Map<String, Object> mapOf(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return (List<Object>) o;
    }
}
