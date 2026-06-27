package de.mhus.vance.addon.brain.workspace;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

/**
 * Typed view on the {@code workspace:} block of an {@code _app.yaml}
 * manifest. Lenient: missing fields fall back to sensible defaults so
 * an LLM-generated manifest with sparse content still mounts.
 */
public record WorkspaceConfig(
        @Nullable String title,
        @Nullable String description,
        @Nullable String landingPage,
        IndexCfg index,
        String defaultPageKind) {

    public static final String APP_NAME = "workspace";

    public record IndexCfg(
            String outputPath,
            String style,
            boolean showDescriptions,
            boolean groupBySection) {}

    public static WorkspaceConfig parse(String manifestBody) {
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
        Object wsRaw = root.get("workspace");
        if (!(wsRaw instanceof Map<?, ?> ws)) {
            // No `workspace:` block — still expose top-level title /
            // description so the sidebar header has the right name.
            return new WorkspaceConfig(
                    topTitle, topDesc, null,
                    new IndexCfg("_index.md", "cards", true, true),
                    "canvas");
        }
        @SuppressWarnings("unchecked") Map<String, Object> wsM = (Map<String, Object>) ws;
        String landing = str(wsM, "landingPage");
        String defaultKind = str(wsM, "defaultPageKind");
        Object idxRaw = wsM.get("index");
        IndexCfg idx;
        if (idxRaw instanceof Map<?, ?> ix) {
            @SuppressWarnings("unchecked") Map<String, Object> ixM = (Map<String, Object>) ix;
            idx = new IndexCfg(
                    strOr(ixM, "outputPath", "_index.md"),
                    strOr(ixM, "style", "cards"),
                    boolOr(ixM, "showDescriptions", true),
                    boolOr(ixM, "groupBySection", true));
        } else {
            idx = new IndexCfg("_index.md", "cards", true, true);
        }
        return new WorkspaceConfig(
                topTitle, topDesc, landing, idx,
                defaultKind == null ? "canvas" : defaultKind);
    }

    public static WorkspaceConfig defaults() {
        return new WorkspaceConfig(
                null,
                null,
                null,
                new IndexCfg("_index.md", "cards", true, true),
                "canvas");
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
}
