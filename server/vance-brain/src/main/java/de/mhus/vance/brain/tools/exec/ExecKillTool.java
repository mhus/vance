package de.mhus.vance.brain.tools.exec;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Force-kills a still-running exec job. Secondary — the LLM should
 * reach for it explicitly after finding a runaway via
 * {@code exec_status}, not reflexively.
 */
@Component
@RequiredArgsConstructor
public class ExecKillTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "id", Map.of(
                            "type", "string",
                            "description", "Job id to kill.")),
            "required", List.of("id"));

    private final ExecManager execManager;

    @Override
    public String name() {
        return "exec_kill";
    }

    @Override
    public String description() {
        return "Force-kill a still-running exec job. Returns killed=false "
                + "if the job already finished or was not found.";
    }

    @Override
    public boolean primary() {
        return false;
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
        boolean killed = execManager.kill(ctx.projectId(), id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("killed", killed);
        return out;
    }
}
