package de.mhus.vance.foot.tools.exec;

import de.mhus.vance.foot.tools.ClientTool;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Looks up a previously submitted client-exec job by id. Returns
 * the same shape as {@code client_exec_run}.
 */
@Component
@RequiredArgsConstructor
public class ClientExecStatusTool implements ClientTool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "id", Map.of(
                            "type", "string",
                            "description", "Job id returned by client_exec_run.")),
            "required", List.of("id"));

    private final ClientExecutorService executor;

    @Override
    public String name() {
        return "client_exec_status";
    }

    @Override
    public String description() {
        return "Check status and inline output of a previously started "
                + "client-exec job by id. Returns the same shape as "
                + "client_exec_run.";
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
        ClientExecJob job = executor.get(id).orElseThrow(() ->
                new IllegalArgumentException("Unknown client-exec job: '" + id + "'"));
        return ClientExecJobRenderer.render(job);
    }
}
