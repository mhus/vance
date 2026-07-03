package de.mhus.vance.addon.brain.workbook.validate;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One extracted {@code ```vance-<type>} fence from a workpage body: its
 * {@code type} (e.g. {@code form}), the YAML-parsed {@code attrs}, the raw
 * body, plus provenance ({@code docPath} + 1-based {@code line}) for finding
 * locations. {@code parseError} is set (and {@code attrs} empty) when the
 * fence body is not valid YAML.
 */
public record FenceBlock(
        String type,
        Map<String, Object> attrs,
        String rawBody,
        String docPath,
        int line,
        @Nullable String parseError) {

    /** Human-readable location for a {@link Finding}. */
    public String location() {
        return docPath + " (```vance-" + type + " @ line " + line + ")";
    }

    /** Trimmed string value of {@code key}, or {@code null} if absent/blank. */
    public @Nullable String str(String key) {
        Object v = attrs.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    /** {@code true} if {@code key} is present but not a boolean. */
    public boolean isNonBoolean(String key) {
        Object v = attrs.get(key);
        return v != null && !(v instanceof Boolean);
    }
}
