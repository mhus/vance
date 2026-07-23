package de.mhus.vance.addon.brain.journal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

/**
 * Typed view on the {@code journal:} block of an {@code _app.yaml}
 * manifest. Lenient: missing fields fall back to sensible defaults so an
 * LLM-generated manifest with sparse content still mounts.
 */
public record JournalConfig(
        @Nullable String title,
        @Nullable String description,
        String entriesDir,
        int indexLimit,
        List<String> moodPresets) {

    public static final String APP_NAME = "journal";

    /** Sub-folder that holds the per-day entries. */
    public static final String DEFAULT_ENTRIES_DIR = "entries";

    /** Default number of recent entries surfaced on the generated index. */
    public static final int DEFAULT_INDEX_LIMIT = 20;

    /** The five mood presets rendered with an icon; free-form values still allowed. */
    public static final List<String> DEFAULT_MOODS =
            List.of("great", "good", "neutral", "low", "bad");

    public static JournalConfig parse(String manifestBody) {
        String yamlPart = manifestBody;
        if (manifestBody.startsWith("---\n")) {
            int end = manifestBody.indexOf("\n---\n", 4);
            if (end > 0) yamlPart = manifestBody.substring(4, end);
        }
        Object loaded = new Yaml().load(yamlPart);
        if (!(loaded instanceof Map<?, ?> m)) {
            return defaults();
        }
        @SuppressWarnings("unchecked") Map<String, Object> root = (Map<String, Object>) m;
        String topTitle = str(root, "title");
        String topDesc = str(root, "description");
        Object journalRaw = root.get("journal");
        if (!(journalRaw instanceof Map<?, ?> jm)) {
            return new JournalConfig(
                    topTitle, topDesc, DEFAULT_ENTRIES_DIR,
                    DEFAULT_INDEX_LIMIT, DEFAULT_MOODS);
        }
        @SuppressWarnings("unchecked") Map<String, Object> jmM = (Map<String, Object>) jm;
        String entriesDir = strOr(jmM, "entriesDir", DEFAULT_ENTRIES_DIR);
        int indexLimit = intOr(jmM, "indexLimit", DEFAULT_INDEX_LIMIT);
        List<String> moods = strList(jmM.get("moodPresets"));
        return new JournalConfig(
                topTitle, topDesc, entriesDir, indexLimit,
                moods.isEmpty() ? DEFAULT_MOODS : moods);
    }

    public static JournalConfig defaults() {
        return new JournalConfig(
                null, null, DEFAULT_ENTRIES_DIR,
                DEFAULT_INDEX_LIMIT, DEFAULT_MOODS);
    }

    private static @Nullable String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static String strOr(Map<String, Object> m, String key, String fallback) {
        String s = str(m, key);
        return s == null || s.isBlank() ? fallback : s;
    }

    private static int intOr(Map<String, Object> m, String key, int fallback) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { /* keep fallback */ }
        }
        return fallback;
    }

    private static List<String> strList(@Nullable Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) out.add(o.toString().trim());
            }
        }
        return out;
    }
}
