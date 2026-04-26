package de.mhus.vance.foot.tools.exec;

import de.mhus.vance.foot.tools.ClientTool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Force-kills a still-running client-exec job. Returns
 * {@code killed=false} when the job already finished or wasn't
 * found.
 */
@Component
@RequiredArgsConstructor
public class ClientExecKillTool implements ClientTool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "id", Map.of(
                            "type", "string",
                            "description", "Job id to kill.")),
            "required", List.of("id"));

    private final ClientExecutorService executor;

    @Override
    public String name() {
        return "client_exec_kill";
    }

    @Override
    public String description() {
        return "Force-kill a still-running client-exec job. Returns "
                + "killed=false if it's already terminal or not found.";
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
    public Map<String, Object> invoke(Map<String, Object> params) {
        Object raw = params == null ? null : params.get("id");
        if (!(raw instanceof String id) || id.isBlank()) {
            throw new IllegalArgumentException("'id' is required");
        }
        boolean killed = executor.kill(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("killed", killed);
        return out;
    }
}
