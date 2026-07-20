package de.mhus.vance.addon.brain.wiki;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

/**
 * Typed view on the {@code wiki:} block of an {@code _app.yaml} manifest.
 * Lenient: missing fields fall back to sensible defaults so an
 * LLM-generated manifest with sparse content still mounts.
 */
public record WikiConfig(
        @Nullable String title,
        @Nullable String description,
        IndexCfg index,
        int recentLimit,
        String defaultPageKind) {

    public static final String APP_NAME = "wiki";

    /** Default number of recently-modified pages surfaced on the root index. */
    public static final int DEFAULT_RECENT_LIMIT = 10;

    public record IndexCfg(
            String outputPath,
            boolean showDescriptions) {}

    public static WikiConfig parse(String manifestBody) {
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
        Object wikiRaw = root.get("wiki");
        if (!(wikiRaw instanceof Map<?, ?> wk)) {
            // No `wiki:` block — still expose top-level title / description
            // so the top-nav header has the right name.
            return new WikiConfig(
                    topTitle, topDesc,
                    new IndexCfg("_index.md", true),
                    DEFAULT_RECENT_LIMIT,
                    "workpage");
        }
        @SuppressWarnings("unchecked") Map<String, Object> wkM = (Map<String, Object>) wk;
        String defaultKind = str(wkM, "defaultPageKind");
        int recent = intOr(wkM, "recentLimit", DEFAULT_RECENT_LIMIT);
        Object idxRaw = wkM.get("index");
        IndexCfg idx;
        if (idxRaw instanceof Map<?, ?> ix) {
            @SuppressWarnings("unchecked") Map<String, Object> ixM = (Map<String, Object>) ix;
            idx = new IndexCfg(
                    strOr(ixM, "outputPath", "_index.md"),
                    boolOr(ixM, "showDescriptions", true));
        } else {
            idx = new IndexCfg("_index.md", true);
        }
        return new WikiConfig(
                topTitle, topDesc, idx, recent,
                defaultKind == null ? "workpage" : defaultKind);
    }

    public static WikiConfig defaults() {
        return new WikiConfig(
                null,
                null,
                new IndexCfg("_index.md", true),
                DEFAULT_RECENT_LIMIT,
                "workpage");
    }

    private static @Nullable String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static String strOr(Map<String, Object> m, String key, String fallback) {
        String s = str(m, key);
        return s == null || s.isBlank() ? fallback : s;
    }

    private static boolean boolOr(Map<String, Object> m, String key, boolean fallback) {
        Object v = m.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return fallback;
    }

    private static int intOr(Map<String, Object> m, String key, int fallback) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { /* keep fallback */ }
        }
        return fallback;
    }
}
