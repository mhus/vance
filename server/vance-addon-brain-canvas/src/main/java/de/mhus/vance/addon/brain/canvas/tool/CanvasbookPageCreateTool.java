package de.mhus.vance.addon.brain.canvas.tool;

import de.mhus.vance.addon.brain.canvas.CanvasbookApplication;
import de.mhus.vance.addon.brain.canvas.CanvasbookFolderReader;
import de.mhus.vance.addon.brain.canvas.CanvasService;
import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Create a single {@code kind: canvas} page inside a canvasbook and refresh the index. */
@Component
@Slf4j
public class CanvasbookPageCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string", "description", "Canvasbook folder."));
                put("title", Map.of("type", "string"));
                put("slug", Map.of("type", "string",
                        "description", "Optional file slug; derived from title if omitted."));
                put("description", Map.of("type", "string"));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder"));

    private final EddieContext eddieContext;
    private final CanvasService canvasService;
    private final CanvasbookApplication application;

    public CanvasbookPageCreateTool(EddieContext eddieContext,
                                    CanvasService canvasService,
                                    CanvasbookApplication application) {
        this.eddieContext = eddieContext;
        this.canvasService = canvasService;
        this.application = application;
    }

    @Override public String name() { return "canvasbook_page_create"; }

    @Override
    public String description() {
        return "Add a new canvas board to a canvasbook and refresh its index. "
                + "Returns the new page path; fill it with `canvas_node_add` / "
                + "`canvas_edge_add`.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() {
        return Set.of("eddie", "write", "document", "canvas");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = CanvasToolSupport.paramString(params, "folder");
        if (folder == null) throw new ToolException("folder is required");
        String normalised = CanvasbookFolderReader.normaliseFolder(folder);
        String title = CanvasToolSupport.paramString(params, "title");
        String description = CanvasToolSupport.paramString(params, "description");
        String slug = CanvasToolSupport.paramString(params, "slug");
        if (slug == null) slug = CanvasbookApplication.slugify(title != null ? title : "canvas");

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        DocumentDocument stored = canvasService.create(
                ctx.tenantId(), project.getName(),
                normalised + "/" + slug, title, description, ctx.userId());

        application.refresh(new VanceApplication.RefreshContext(
                ctx.tenantId(), project.getName(), normalised, ctx.userId(), ctx.processId()));

        log.info("CanvasbookPageCreateTool folder='{}' path='{}'", normalised, stored.getPath());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", stored.getPath());
        result.put("id", stored.getId());
        if (title != null) result.put("title", title);
        result.put("nextStep", "Fill the board with `canvas_node_add(path=\""
                + stored.getPath() + "\", node={...})`.");
        return result;
    }
}
