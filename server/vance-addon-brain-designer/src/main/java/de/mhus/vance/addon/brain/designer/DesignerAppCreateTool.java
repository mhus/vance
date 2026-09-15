package de.mhus.vance.addon.brain.designer;

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
 * Bootstrap a designer-app folder. Writes the {@code _app.yaml} manifest
 * and runs an immediate refresh so the {@code _index.md} catalogue exists
 * for any designs already in the folder.
 *
 * <p>Design files themselves are written with the standard document tools
 * — this tool deliberately writes nothing but the manifest. The typical
 * workflow is:
 * <ol>
 *   <li>LLM calls {@code designer_app_create(folder, title, ...)}.</li>
 *   <li>LLM writes designs with {@code doc_write} — one subfolder per
 *       design, {@code index.html} entry each.</li>
 *   <li>The user opens {@code _app.yaml} in the app editor and sees the
 *       live preview.</li>
 * </ol>
 */
@Component
@Slf4j
public class DesignerAppCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type",
            "object",
            "properties",
            new LinkedHashMap<String, Object>() {
                {
                    put(
                            "folder",
                            Map.of(
                                    "type",
                                    "string",
                                    "description",
                                    "Folder for the designer app. "
                                            + "Manifest lives at <folder>/_app.yaml, designs are "
                                            + "subfolders with an index.html each."));
                    put("title", Map.of("type", "string"));
                    put("description", Map.of("type", "string"));
                    put(
                            "overwrite",
                            Map.of(
                                    "type",
                                    "boolean",
                                    "description",
                                    "Allow replacing an existing _app.yaml. " + "Default false."));
                    put("projectId", Map.of("type", "string"));
                }
            },
            "required",
            List.of("folder"));

    private final EddieContext eddieContext;
    private final DesignerApplication designerApplication;

    public DesignerAppCreateTool(EddieContext eddieContext, DesignerApplication designerApplication) {
        this.eddieContext = eddieContext;
        this.designerApplication = designerApplication;
    }

    @Override
    public String name() {
        return "designer_app_create";
    }

    @Override
    public String description() {
        return "Bootstrap a designer-app folder (a collection of web page "
                + "designs). Writes _app.yaml + runs refresh to produce the "
                + "_index.md catalogue. Designs themselves are written with the "
                + "standard doc_write tool — one subfolder per design with an "
                + "index.html entry. Embed the resulting `markdownLink` so the "
                + "user can open the design preview with one click.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Set<String> labels() {
        return Set.of("eddie", "write", "document", "designer", "application");
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

        String normalised;
        try {
            normalised = DesignerPaths.normaliseFolder(folder);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage(), e);
        }

        VanceApplication.CreateContext cc = new VanceApplication.CreateContext(
                ctx.tenantId(),
                project.getName(),
                normalised,
                ctx.userId(),
                ctx.processId(),
                paramBoolean(params, "overwrite"),
                createParams);

        VanceApplication.CreateResult result = designerApplication.create(cc);

        log.info(
                "DesignerAppCreateTool tenant='{}' folder='{}' manifestPath='{}'",
                ctx.tenantId(),
                folder,
                result.manifestPath());

        return result.toMap();
    }

    // ── Helpers ───────────────────────────────────────────────────

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
