package de.mhus.vance.brain.tools.exec;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Looks up a previously submitted exec job by id, within the caller's
 * session scope. Returns the same shape as {@code exec_run}.
 */
@Component
@RequiredArgsConstructor
public class ExecStatusTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "id", Map.of(
                            "type", "string",
                            "description", "Job id returned by exec_run.")),
            "required", List.of("id"));

    private final ExecManager execManager;
    private final ExecProperties properties;

    @Override
    public String name() {
        return "exec_status";
    }

    @Override
    public String description() {
        return "Check status and inline output of a previously started exec "
                + "job by id. Returns the same shape as exec_run.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Object raw = params == null ? null : params.get("id");
        if (!(raw instanceof String id) || id.isBlank()) {
            throw new ToolException("'id' is required");
        }
        ExecJob job = execManager.get(ctx.projectId(), id)
                .orElseThrow(() -> new ToolException(
                        "Unknown exec job: '" + id + "' (not in this project)"));
        return ExecJobRenderer.render(job, properties.getInlineOutputCharCap());
    }
}
