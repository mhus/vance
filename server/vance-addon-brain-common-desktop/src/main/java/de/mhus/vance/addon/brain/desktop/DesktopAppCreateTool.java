package de.mhus.vance.addon.brain.desktop;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * One-shot bootstrap for a Common Desktop folder. Writes the
 * {@code _app.yaml} manifest with the correct schema and produces the
 * first {@code _desktop.md} snapshot. Preferred over hand-writing the
 * manifest via {@code doc_create}.
 */
@Component
@Slf4j
public class DesktopAppCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string",
                        "description", "Folder for the desktop. Apps under "
                                + "it are listed. Manifest lives at "
                                + "<folder>/_app.yaml."));
                put("title", Map.of("type", "string"));
                put("description", Map.of("type", "string"));
                put("recurse", Map.of("type", "boolean",
                        "description", "false (default) = only direct child "
                                + "apps; true = the whole subtree."));
                put("root", Map.of("type", "string",
                        "description", "Scan root. '.' or absent = the "
                                + "desktop folder; a leading '/' = a "
                                + "project-absolute path."));
                put("include", Map.of("type", "array",
                        "items", Map.of("type", "string"),
                        "description", "If non-empty, ONLY these app types "
                                + "are shown."));
                put("exclude", Map.of("type", "array",
                        "items", Map.of("type", "string"),
                        "description", "App types to hide. (Other "
                                + "common-desktops are always excluded.)"));
                put("order", Map.of("type", "array",
                        "items", Map.of("type", "string"),
                        "description", "App types to place first, in this "
                                + "order. The rest follow by folder name."));
                put("overwrite", Map.of("type", "boolean",
                        "description", "Allow replacing an existing "
                                + "_app.yaml. Default false."));
                put("projectId", Map.of("type", "string",
                        "description", "Default: active project."));
            }},
            "required", List.of("folder"));

    private final EddieContext eddieContext;
    private final DesktopApplication desktopApplication;

    public DesktopAppCreateTool(EddieContext eddieContext,
                                DesktopApplication desktopApplication) {
        this.eddieContext = eddieContext;
        this.desktopApplication = desktopApplication;
    }

    @Override public String name() { return "desktop_app_create"; }

    @Override
    public String description() {
        return "Create a Common Desktop — a launcher + status board over "
                + "the apps under `folder`. Writes the _app.yaml manifest "
                + "and the first _desktop.md snapshot in one call. Use "
                + "this instead of hand-writing _app.yaml.";
    }

    @Override public boolean primary() { return false; }

    @Override
    public Set<String> labels() {
        return Set.of("eddie", "write", "document", "common-desktop", "application");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = paramString(params, "folder");
        if (folder == null) throw new ToolException("folder is required");

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);

        Map<String, Object> createParams = new LinkedHashMap<>();
        copyIfPresent(params, createParams, "title");
        copyIfPresent(params, createParams, "description");
        copyIfPresent(params, createParams, "recurse");
        copyIfPresent(params, createParams, "root");
        copyIfPresent(params, createParams, "include");
        copyIfPresent(params, createParams, "exclude");
        copyIfPresent(params, createParams, "order");

        VanceApplication.CreateContext cc = new VanceApplication.CreateContext(
                ctx.tenantId(), project.getName(), normaliseFolder(folder),
                ctx.userId(), ctx.processId(),
                paramBoolean(params, "overwrite"),
                createParams);

        VanceApplication.CreateResult result = desktopApplication.create(cc);

        log.info("DesktopAppCreateTool tenant='{}' folder='{}' manifestPath='{}'",
                ctx.tenantId(), folder, result.manifestPath());

        return result.toMap();
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static String normaliseFolder(String folder) {
        String f = folder.trim();
        while (f.endsWith("/")) f = f.substring(0, f.length() - 1);
        while (f.startsWith("/")) f = f.substring(1);
        if (f.isEmpty()) throw new ToolException("folder must not be empty");
        return f;
    }

    private static @Nullable String paramString(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static boolean paramBoolean(@Nullable Map<String, Object> params, String key) {
        if (params == null) return false;
        Object v = params.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return false;
    }

    private static void copyIfPresent(Map<String, Object> src, Map<String, Object> dst, String key) {
        Object v = src == null ? null : src.get(key);
        if (v != null) dst.put(key, v);
    }
}
