package de.mhus.vance.addon.brain.issues;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

/**
 * Typed view on the {@code issues:} block of an {@code _app.yaml} manifest,
 * plus a renderer that rebuilds the manifest body (used when the number
 * counter is bumped). Lenient defaults so a sparse manifest still mounts.
 */
public record IssuesConfig(
        @Nullable String title,
        @Nullable String description,
        String itemsDir,
        String archiveDir,
        int nextNumber,
        List<String> suggestedLabels) {

    public static final String APP_NAME = "issues";
    public static final String DEFAULT_ITEMS_DIR = "items";
    public static final String DEFAULT_ARCHIVE_DIR = "archive";

    public IssuesConfig withNextNumber(int n) {
        return new IssuesConfig(title, description, itemsDir, archiveDir, n, suggestedLabels);
    }

    public static IssuesConfig parse(String manifestBody) {
        String yamlPart = manifestBody;
        if (manifestBody.startsWith("---\n")) {
            int end = manifestBody.indexOf("\n---\n", 4);
            if (end > 0) yamlPart = manifestBody.substring(4, end);
        }
        Object loaded = new Yaml().load(yamlPart);
        if (!(loaded instanceof Map<?, ?> m)) return defaults();
        @SuppressWarnings("unchecked") Map<String, Object> root = (Map<String, Object>) m;
        String topTitle = str(root, "title");
        String topDesc = str(root, "description");
        Object issuesRaw = root.get("issues");
        if (!(issuesRaw instanceof Map<?, ?> im)) {
            return new IssuesConfig(topTitle, topDesc, DEFAULT_ITEMS_DIR,
                    DEFAULT_ARCHIVE_DIR, 1, new ArrayList<>());
        }
        @SuppressWarnings("unchecked") Map<String, Object> imM = (Map<String, Object>) im;
        return new IssuesConfig(
                topTitle, topDesc,
                strOr(imM, "itemsDir", DEFAULT_ITEMS_DIR),
                strOr(imM, "archiveDir", DEFAULT_ARCHIVE_DIR),
                intOr(imM, "nextNumber", 1),
                strList(imM.get("labels")));
    }

    public static IssuesConfig defaults() {
        return new IssuesConfig(null, null, DEFAULT_ITEMS_DIR, DEFAULT_ARCHIVE_DIR, 1, new ArrayList<>());
    }

    /** Rebuild the {@code _app.yaml} manifest body from this config. */
    public String render() {
        StringBuilder mb = new StringBuilder();
        mb.append("$meta:\n  kind: application\n  app: issues\n");
        if (title != null && !title.isBlank()) mb.append("title: \"").append(escape(title)).append("\"\n");
        if (description != null && !description.isBlank()) {
            mb.append("description: \"").append(escape(description)).append("\"\n");
        }
        mb.append("issues:\n");
        mb.append("  itemsDir: ").append(itemsDir).append('\n');
        mb.append("  archiveDir: ").append(archiveDir).append('\n');
        mb.append("  nextNumber: ").append(nextNumber).append('\n');
        if (!suggestedLabels.isEmpty()) {
            mb.append("  labels: [")
                    .append(String.join(", ", suggestedLabels.stream().map(IssuesConfig::quote).toList()))
                    .append("]\n");
        }
        return mb.toString();
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
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { /* keep */ }
        }
        return fallback;
    }
    private static List<String> strList(@Nullable Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) if (o != null && !o.toString().isBlank()) out.add(o.toString().trim());
        }
        return out;
    }
    private static String escape(String s) { return s.replace("\"", "\\\""); }
    private static String quote(String s) { return "\"" + s.replace("\"", "\\\"") + "\""; }
}
