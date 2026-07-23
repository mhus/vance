package de.mhus.vance.addon.brain.gtd;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

/**
 * Typed view on the {@code gtd:} block of an {@code _app.yaml} manifest.
 * Lenient defaults so a sparse LLM-generated manifest still mounts.
 */
public record GtdConfig(
        @Nullable String title,
        @Nullable String description,
        String inboxDir,
        String actionsDir,
        String projectsDir,
        List<String> suggestedContexts) {

    public static final String APP_NAME = "gtd";
    public static final String DEFAULT_INBOX_DIR = "inbox";
    public static final String DEFAULT_ACTIONS_DIR = "actions";
    public static final String DEFAULT_PROJECTS_DIR = "projects";

    public static GtdConfig parse(String manifestBody) {
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
        Object gtdRaw = root.get("gtd");
        if (!(gtdRaw instanceof Map<?, ?> gm)) {
            return new GtdConfig(topTitle, topDesc, DEFAULT_INBOX_DIR,
                    DEFAULT_ACTIONS_DIR, DEFAULT_PROJECTS_DIR, new ArrayList<>());
        }
        @SuppressWarnings("unchecked") Map<String, Object> gmM = (Map<String, Object>) gm;
        return new GtdConfig(
                topTitle, topDesc,
                strOr(gmM, "inboxDir", DEFAULT_INBOX_DIR),
                strOr(gmM, "actionsDir", DEFAULT_ACTIONS_DIR),
                strOr(gmM, "projectsDir", DEFAULT_PROJECTS_DIR),
                strList(gmM.get("contexts")));
    }

    public static GtdConfig defaults() {
        return new GtdConfig(null, null, DEFAULT_INBOX_DIR,
                DEFAULT_ACTIONS_DIR, DEFAULT_PROJECTS_DIR, new ArrayList<>());
    }

    private static @Nullable String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }
    private static String strOr(Map<String, Object> m, String key, String fallback) {
        String s = str(m, key);
        return s == null || s.isBlank() ? fallback : s;
    }
    private static List<String> strList(@Nullable Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) if (o != null && !o.toString().isBlank()) out.add(o.toString().trim());
        }
        return out;
    }
}
