package de.mhus.vance.exec;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Typed reads of the loosely typed parameter map the {@code file_*} search tools
 * share, with the messages the tools have always given.
 */
final class SearchParams {

    private final Map<String, Object> params;

    SearchParams(@Nullable Map<String, Object> params) {
        this.params = params == null ? Map.of() : params;
    }

    String required(String key) {
        Object raw = params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new RootDirException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }

    @Nullable
    String string(String key) {
        Object raw = params.get(key);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    @Nullable
    Integer integer(String key) {
        return params.get(key) instanceof Number n ? n.intValue() : null;
    }

    @Nullable
    Long number(String key) {
        return params.get(key) instanceof Number n ? n.longValue() : null;
    }

    boolean flag(String key) {
        return Boolean.TRUE.equals(params.get(key));
    }

    @Nullable
    Instant instant(String key) {
        String raw = string(key);
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new RootDirException("'" + key + "' must be ISO-8601 (e.g. 2026-01-01T00:00:00Z); got: " + raw, e);
        }
    }
}
