package de.mhus.vance.brain.tools.exec;

import de.mhus.vance.brain.execution.ExecutionRouter;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Compact status snapshot of a running or finished exec job — no
 * stdout/stderr bodies. Cheap enough to poll; pair with {@code work_exec_tail}
 * when the LLM needs to see actual output. {@code lastOutputAt} answers
 * "is this thing still doing anything?" honestly.
 */
@Component
@RequiredArgsConstructor
public class ExecStatTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "id", Map.of(
                            "type", "string",
                            "description", "Job id returned by work_exec_run.")),
            "required", List.of("id"));

    private final ExecutionRouter router;

    @Override
    public String name() {
        return "work_exec_stat";
    }

    @Override
    public String description() {
        return "Compact status of an exec job: status, startedAt, "
                + "lastOutputAt, finishedAt, exitCode, runtime, log "
                + "sizes/mtimes. Routes via the cross-side execution "
                + "registry so brain- and foot-side jobs answer the "
                + "same way. No stdout/stderr bodies — use work_exec_tail "
                + "for those.";
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
    public Set<String> labels() {
        return Set.of("read-only");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Object raw = params == null ? null : params.get("id");
        if (!(raw instanceof String id) || id.isBlank()) {
            throw new ToolException("'id' is required");
        }
        return router.stat(id, ctx.tenantId());
    }

    public static Map<String, Object> render(ExecStat s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", s.id());
        out.put("status", s.status().name());
        out.put("command", s.command());
        out.put("startedAt", s.startedAt().toString());
        out.put("lastOutputAt", s.lastOutputAt().toString());
        if (s.finishedAt() != null) {
            out.put("finishedAt", s.finishedAt().toString());
        }
        out.put("durationMs", s.durationMs());
        if (s.exitCode() != null) {
            out.put("exitCode", s.exitCode());
        }
        out.put("stdoutBytes", s.stdoutBytes());
        out.put("stderrBytes", s.stderrBytes());
        if (s.stdoutMtimeMillis() > 0) {
            out.put("stdoutMtime", Instant.ofEpochMilli(s.stdoutMtimeMillis()).toString());
        }
        if (s.stderrMtimeMillis() > 0) {
            out.put("stderrMtime", Instant.ofEpochMilli(s.stderrMtimeMillis()).toString());
        }
        out.put("stdoutPath", s.stdoutPath());
        out.put("stderrPath", s.stderrPath());
        return out;
    }
}
