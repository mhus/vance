package de.mhus.vance.shared.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sanitizes map keys for MongoDB storage. Mongo reads a dot in a field name
 * as a path separator, so a user-/LLM-chosen key containing {@code '.'}
 * (e.g. {@code "file.txt"}) either breaks the persist or nests unexpectedly.
 * Replacing dots with {@code '_'} before {@code save()} is a project-wide
 * gotcha — this is the single shared helper for it.
 *
 * <p>{@link #sanitizeKeys(Map)} rewrites keys recursively through nested
 * maps and lists, returning a fresh structure (the input is left untouched).
 */
public final class MongoKeys {

    private MongoKeys() {}

    /** The escape applied to any {@code '.'} in a map key. */
    public static String sanitizeKey(String key) {
        return key.replace('.', '_');
    }

    /**
     * Returns a deep copy of {@code value} with every map key dot-sanitized.
     * Maps and lists are walked recursively; scalars pass through unchanged.
     */
    @SuppressWarnings("unchecked")
    public static Object sanitize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                out.put(sanitizeKey(key), sanitize(e.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) {
                out.add(sanitize(o));
            }
            return out;
        }
        return value;
    }

    /** Convenience for the common {@code Map<String,Object>} payload case. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> sanitizeKeys(Map<String, Object> payload) {
        return (Map<String, Object>) sanitize(payload);
    }
}
