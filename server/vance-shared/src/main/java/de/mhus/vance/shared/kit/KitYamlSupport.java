package de.mhus.vance.shared.kit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Reading primitives shared by every kit file.
 *
 * <p>{@link KitDescriptorParser} (here, read by brain and store) and the
 * brain's {@code KitYamlMapper} read the same kind of YAML with the same
 * leniencies: a blank string is absent, a boolean may be written as text, an
 * unreadable timestamp is absent. One copy, so a field cannot be lenient in
 * one file and strict in the next by accident.
 */
public final class KitYamlSupport {

    private KitYamlSupport() {}

    /**
     * Load a document that must be a top-level map; empty text is an empty
     * map. A repeated key replaces the earlier one — SnakeYAML's default,
     * and how the kit files have always been read.
     */
    public static Map<String, Object> loadMap(String yamlText, String label) {
        return loadMap(yamlText, label, true);
    }

    /**
     * @param allowDuplicateKeys {@code false} refuses a repeated key instead
     *        of letting it silently replace the earlier one — for documents
     *        where which value wins must not depend on the order of lines
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> loadMap(String yamlText, String label, boolean allowDuplicateKeys) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(allowDuplicateKeys);
        Yaml yaml = new Yaml(options);
        Object parsed;
        try {
            parsed = yaml.load(yamlText);
        } catch (RuntimeException e) {
            throw new KitException("Failed to parse " + label + ": " + e.getMessage(), e);
        }
        if (parsed == null) {
            return new LinkedHashMap<>();
        }
        if (!(parsed instanceof Map<?, ?> m)) {
            throw new KitException(label + " must be a top-level map");
        }
        return (Map<String, Object>) m;
    }

    /** The trimmed value; missing or blank is a {@link KitException}. */
    public static String requireString(Map<String, Object> map, String key, String label) {
        Object v = map.get(key);
        if (v == null) {
            throw new KitException(label + ": missing required field '" + key + "'");
        }
        String s = v.toString().trim();
        if (s.isEmpty()) {
            throw new KitException(label + ": field '" + key + "' must not be blank");
        }
        return s;
    }

    /** The trimmed value, or null when it is absent or blank. */
    public static @Nullable String stringOrNull(@Nullable Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    public static boolean booleanOrFalse(@Nullable Object v) {
        return booleanOr(v, false);
    }

    /** A YAML boolean, or text that reads as one; absent is {@code defaultValue}. */
    public static boolean booleanOr(@Nullable Object v, boolean defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString().trim());
    }

    /**
     * A timestamp as SnakeYAML hands it over ({@link java.util.Date} for an
     * unquoted one) or as ISO-8601 text; null when absent or unreadable.
     */
    public static @Nullable Instant parseInstant(@Nullable Object v) {
        if (v == null) return null;
        if (v instanceof Instant i) return i;
        if (v instanceof java.util.Date d) return d.toInstant();
        try {
            return Instant.parse(v.toString().trim());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
