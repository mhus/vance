package de.mhus.vance.brain.tools.slideshow;

import de.mhus.vance.brain.applications.SlideshowApplication;
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
 * Bootstrap a slideshow-app folder. Writes the {@code _app.yaml}
 * manifest and runs an immediate refresh to produce
 * {@code _index.yaml} for any images already in the folder.
 *
 * <p>Images themselves are uploaded with the standard document tools
 * — this tool deliberately doesn't accept image data. The typical
 * workflow is:
 * <ol>
 *   <li>User uploads images via the Documents editor or
 *       {@code doc_create} tool.</li>
 *   <li>LLM calls {@code slideshow_app_create(folder, title, ...)}.</li>
 *   <li>The user opens {@code _app.yaml} in the App editor.</li>
 * </ol>
 */
@Component
@Slf4j
public class SlideshowAppCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string",
                        "description", "Folder for the slideshow app. "
                                + "Manifest lives at <folder>/_app.yaml."));
                put("title", Map.of("type", "string"));
                put("description", Map.of("type", "string"));
                put("order", Map.of("type", "array",
                        "items", Map.of("type", "string"),
                        "description", "Optional explicit slide order — "
                                + "list of paths relative to the folder. "
                                + "Sorted alphabetically by filename when omitted."));
                put("captions", Map.of("type", "object",
                        "description", "Optional per-slide caption map "
                                + "(relative-path → caption text). "
                                + "Filename stem is used when missing."));
                put("autoplaySeconds", Map.of("type", "integer",
                        "description", "Auto-advance interval in seconds. "
                                + "0 / missing = manual navigation only."));
                put("aspectRatio", Map.of("type", "string",
                        "description", "Optional viewport hint, e.g. '16:9'. "
                                + "Default lets each slide use its own ratio."));
                put("overwrite", Map.of("type", "boolean",
                        "description", "Allow replacing an existing _app.yaml. "
                                + "Default false."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder"));

    private final EddieContext eddieContext;
    private final SlideshowApplication slideshowApplication;

    public SlideshowAppCreateTool(EddieContext eddieContext,
                                  SlideshowApplication slideshowApplication) {
        this.eddieContext = eddieContext;
        this.slideshowApplication = slideshowApplication;
    }

    @Override public String name() { return "slideshow_app_create"; }

    @Override
    public String description() {
        return "Bootstrap a slideshow-app folder. Writes _app.yaml + "
                + "runs refresh to produce _index.yaml. Image uploads "
                + "go through the standard doc_create / Documents "
                + "editor — this tool only writes the manifest. "
                + "Embed the resulting `markdownLink` so the user can "
                + "open the interactive viewer with one click.";
    }

    @Override public boolean primary() { return false; }

    @Override
    public Set<String> labels() {
        return Set.of("eddie", "write", "document", "slideshow", "application");
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
        copyIfPresent(params, createParams, "order");
        copyIfPresent(params, createParams, "captions");
        copyIfPresent(params, createParams, "autoplaySeconds");
        copyIfPresent(params, createParams, "aspectRatio");

        VanceApplication.CreateContext cc = new VanceApplication.CreateContext(
                ctx.tenantId(), project.getName(), normaliseFolder(folder),
                ctx.userId(), ctx.processId(),
                paramBoolean(params, "overwrite"),
                createParams);

        VanceApplication.CreateResult result = slideshowApplication.create(cc);

        log.info("SlideshowAppCreateTool tenant='{}' folder='{}' manifestPath='{}'",
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
