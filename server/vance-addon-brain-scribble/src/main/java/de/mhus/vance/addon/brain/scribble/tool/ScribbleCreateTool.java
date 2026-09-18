package de.mhus.vance.addon.brain.scribble.tool;

import de.mhus.vance.addon.brain.scribble.ScribbleService;
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

/** Create a new empty {@code kind: scribble} document (a handwriting sheet). */
@Component
@Slf4j
public class ScribbleCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type",
            "object",
            "properties",
            new LinkedHashMap<String, Object>() {
                {
                    put(
                            "path",
                            Map.of(
                                    "type",
                                    "string",
                                    "description",
                                    "Target document path (without leading slash). "
                                            + "Auto-suffixed with `.scribble.yaml` if no extension is given."));
                    put("title", Map.of("type", "string"));
                    put("projectId", Map.of("type", "string"));
                }
            },
            "required",
            List.of("path"));

    private final EddieContext eddieContext;
    private final ScribbleService scribbleService;

    public ScribbleCreateTool(EddieContext eddieContext, ScribbleService scribbleService) {
        this.eddieContext = eddieContext;
        this.scribbleService = scribbleService;
    }

    @Override
    public String name() {
        return "scribble_create";
    }

    @Override
    public String description() {
        return "Create a new handwriting sheet (kind: scribble) — vector pen strokes "
                + "on an A4-like raster, written by the user with the pen editor. "
                + "Stored as YAML with a `$meta.kind: scribble` header. Path is "
                + "auto-suffixed with `.scribble.yaml` if no extension is given. "
                + "There are no stroke tools: handwriting is user input, not LLM output.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Set<String> labels() {
        return Set.of("eddie", "write", "document", "scribble");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String path = ScribbleToolSupport.paramString(params, "path");
        if (path == null) throw new ToolException("path is required");
        String title = ScribbleToolSupport.paramString(params, "title");

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        DocumentDocument stored = scribbleService.create(ctx.tenantId(), project.getName(), path, title, ctx.userId());

        log.info("ScribbleCreateTool path='{}' title='{}'", stored.getPath(), title);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", stored.getPath());
        result.put("id", stored.getId());
        if (title != null) result.put("title", title);
        result.put(
                "nextStep",
                "The user writes on the sheet with the pen editor. "
                        + "Check its structure with `scribble_validate(path)`.");
        return result;
    }
}
