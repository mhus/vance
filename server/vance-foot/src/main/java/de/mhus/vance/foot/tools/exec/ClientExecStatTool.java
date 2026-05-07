package de.mhus.vance.foot.tools.exec;

import de.mhus.vance.foot.tools.ClientTool;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Compact status snapshot of a client-side exec job — no inline output
 * bodies. Mirror of brain {@code exec_stat}.
 */
@Component
@RequiredArgsConstructor
public class ClientExecStatTool implements ClientTool {

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
        return "client_exec_stat";
    }

    @Override
    public String description() {
        return "Compact status of a client-exec job: status, startedAt, "
                + "lastOutputAt, finishedAt, exitCode, runtime, log "
                + "sizes/mtimes. No stdout/stderr bodies — use "
                + "client_exec_tail for those.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public java.util.Set<String> labels() {
        return java.util.Set.of("read-only");
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
        ClientExecStat stat = executor.stat(id).orElseThrow(() ->
                new IllegalArgumentException("Unknown client-exec job: '" + id + "'"));
        return render(stat);
    }

    static Map<String, Object> render(ClientExecStat s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", s.id());
        out.put("status", s.status().name());
        out.put("command", s.command());
        if (s.sessionId() != null) {
            out.put("sessionId", s.sessionId());
        }
        if (s.projectId() != null) {
            out.put("projectId", s.projectId());
        }
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
