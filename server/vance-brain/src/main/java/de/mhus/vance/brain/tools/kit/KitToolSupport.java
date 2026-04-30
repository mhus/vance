package de.mhus.vance.brain.tools.kit;

import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitOperationResultDto;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Shared helpers for the kit tool wrappers — argument extraction,
 * result-DTO serialization to a tool-friendly map.
 */
final class KitToolSupport {

    private KitToolSupport() {}

    static String requireProject(ToolInvocationContext ctx, @Nullable String override) {
        String p = override == null || override.isBlank() ? ctx.projectId() : override;
        if (p == null || p.isBlank()) {
            throw new ToolException("kit tools require a project — pass `project` or "
                    + "invoke from within a project scope");
        }
        return p;
    }

    static @Nullable String optionalString(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    static String requireString(Map<String, Object> params, String key) {
        String v = optionalString(params, key);
        if (v == null) {
            throw new ToolException("missing required parameter: " + key);
        }
        return v;
    }

    static boolean optionalBoolean(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString().trim());
    }

    static KitInheritDto sourceFrom(Map<String, Object> params) {
        return KitInheritDto.builder()
                .url(requireString(params, "url"))
                .path(optionalString(params, "path"))
                .branch(optionalString(params, "branch"))
                .commit(optionalString(params, "commit"))
                .build();
    }

    static Map<String, Object> resultToMap(KitOperationResultDto r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kit", r.getKitName());
        out.put("mode", r.getMode());
        if (r.getSourceCommit() != null) out.put("commit", r.getSourceCommit());
        putIfPresent(out, "documentsAdded", r.getDocumentsAdded());
        putIfPresent(out, "documentsUpdated", r.getDocumentsUpdated());
        putIfPresent(out, "documentsRemoved", r.getDocumentsRemoved());
        putIfPresent(out, "settingsAdded", r.getSettingsAdded());
        putIfPresent(out, "settingsUpdated", r.getSettingsUpdated());
        putIfPresent(out, "settingsRemoved", r.getSettingsRemoved());
        putIfPresent(out, "toolsAdded", r.getToolsAdded());
        putIfPresent(out, "toolsUpdated", r.getToolsUpdated());
        putIfPresent(out, "toolsRemoved", r.getToolsRemoved());
        putIfPresent(out, "skippedPasswords", r.getSkippedPasswords());
        putIfPresent(out, "inheritedKits", r.getInheritedKits());
        putIfPresent(out, "warnings", r.getWarnings());
        return out;
    }

    private static void putIfPresent(Map<String, Object> map, String key, List<String> list) {
        if (list != null && !list.isEmpty()) map.put(key, list);
    }

    static Map<String, Object> sourceSchemaProps() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("url", Map.of("type", "string",
                "description", "Source repo URL (https://, file://, or absolute path)"));
        props.put("path", Map.of("type", "string",
                "description", "Sub-path inside the repo. Defaults to repo root."));
        props.put("branch", Map.of("type", "string",
                "description", "Branch name. Defaults to main."));
        props.put("commit", Map.of("type", "string",
                "description", "Pin a commit SHA. Wins over branch when set."));
        props.put("token", Map.of("type", "string",
                "description", "Auth token for HTTPS repos."));
        props.put("project", Map.of("type", "string",
                "description", "Target project. Defaults to the current project."));
        return props;
    }
}
