package de.mhus.vance.brain.milliways;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A share about to happen: the scope plus the user's form submission.
 *
 * <p>{@link #values} is keyed by {@code FormFieldDto.name} of the form the
 * handler itself declared, so the handler knows the shape. It is still
 * untrusted input — nothing validates it on the way in except JSON
 * parsing, which is why the accessors below coerce rather than cast.
 */
public record ShareRequest(ShareScope scope, Map<String, Object> values) {

    public ShareRequest {
        // Not Map.copyOf: it throws NullPointerException on a null value, and
        // `values` is raw JSON — {"values":{"text":null}} is a well-formed body
        // that would have produced a 500 for what is at most a 422. A key with
        // no value says the same thing as an absent key, so it is dropped; the
        // accessors above already treat both as "not given".
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) copy.put(e.getKey(), e.getValue());
        }
        values = Collections.unmodifiableMap(copy);
    }

    /** Trimmed non-blank string for {@code key}, or {@code null}. */
    public @Nullable String string(String key) {
        Object raw = values.get(key);
        if (raw instanceof String s && !s.isBlank()) return s.trim();
        return null;
    }

    /** Trimmed non-blank string for {@code key}, or the given fallback. */
    public String stringOr(String key, String fallback) {
        String v = string(key);
        return v == null ? fallback : v;
    }

    /**
     * Non-blank trimmed strings for {@code key}, accepting either a JSON
     * array (a {@code multi_select}) or a single string (a {@code select}
     * on the same field name). Duplicates are dropped, order kept.
     */
    public List<String> strings(String key) {
        Object raw = values.get(key);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw instanceof Collection<?> col) {
            for (Object o : col) {
                if (o instanceof String s && !s.isBlank()) out.add(s.trim());
            }
        } else if (raw instanceof String s && !s.isBlank()) {
            out.add(s.trim());
        }
        return List.copyOf(out);
    }

    /** Shorthand for {@code scope().tenantId()}. */
    public String tenantId() {
        return scope.tenantId();
    }
}
