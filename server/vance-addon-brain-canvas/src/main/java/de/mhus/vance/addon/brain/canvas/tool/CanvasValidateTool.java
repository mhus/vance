package de.mhus.vance.addon.brain.canvas.tool;

import de.mhus.vance.addon.brain.canvas.CanvasValidationService;
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
import org.springframework.stereotype.Component;

/**
 * Read-only static check of a {@code kind: canvas} board: parses it and
 * verifies structural integrity — no duplicate node/edge ids, every edge
 * connects existing nodes, every {@code parent} points at a real group,
 * no self-parenting. Use it after building or editing a canvas to
 * self-check before telling the user it's done. Returns
 * {@code { ok, errors, warnings, findings[] }}.
 */
@Component
@Slf4j
public class CanvasValidateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("path", Map.of("type", "string",
                        "description", "Path of a kind: canvas document (e.g. "
                                + "'design/ideen.canvas.yaml')."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("path"));

    private final EddieContext eddieContext;
    private final CanvasValidationService validationService;

    public CanvasValidateTool(EddieContext eddieContext,
                              CanvasValidationService validationService) {
        this.eddieContext = eddieContext;
        this.validationService = validationService;
    }

    @Override public String name() { return "canvas_validate"; }

    @Override
    public String description() {
        return "Statically validate a canvas board: parses it and checks for "
                + "duplicate node/edge ids, edges pointing at missing nodes, and "
                + "`parent` references that don't resolve to a group. Read-only. "
                + "Returns { ok, errors, warnings, findings[] }. Run it after "
                + "building/editing a canvas to self-check.";
    }

    @Override public boolean primary() { return false; }

    @Override public boolean contributesPrak() { return false; }

    @Override public Set<String> labels() {
        return Set.of("eddie", "read-only", "document", "canvas");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Object raw = params == null ? null : params.get("path");
        if (!(raw instanceof String path) || path.isBlank()) {
            throw new ToolException("'path' is required");
        }
        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        CanvasValidationService.Result result =
                validationService.validate(ctx.tenantId(), project.getName(), path.trim());
        log.info("CanvasValidateTool path='{}' ok={} findings={}",
                path, result.ok(), result.findings().size());
        return result.toMap();
    }
}
