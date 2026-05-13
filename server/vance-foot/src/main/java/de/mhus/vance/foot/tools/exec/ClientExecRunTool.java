package de.mhus.vance.foot.tools.exec;

import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.tools.ClientTool;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Runs a shell command on the foot host and waits up to {@code waitMs}
 * (default 15 s) for completion. If the job finishes in time you get
 * status + stdout + stderr; otherwise the response carries the job id
 * and {@code status=RUNNING} — follow up with {@code client_exec_status}.
 */
@Component
@RequiredArgsConstructor
public class ClientExecRunTool implements ClientTool {

    private static final long DEFAULT_WAIT_MS = 15_000;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "command", Map.of(
                            "type", "string",
                            "description",
                                    "Shell command to run on the user's machine "
                                            + "(bash via /bin/sh -c on Linux/macOS, "
                                            + "cmd.exe /c on Windows). Full shell syntax "
                                            + "allowed; CWD is the foot's working dir."),
                    "waitMs", Map.of(
                            "type", "integer",
                            "description",
                                    "Milliseconds to wait for completion before returning. "
                                            + "Default 15 000."),
                    "deadlineSeconds", Map.of(
                            "type", "integer",
                            "description",
                                    "Optional hard-kill deadline (seconds from now). "
                                            + "If the subprocess is still running when "
                                            + "the deadline passes the foot kills it "
                                            + "forcibly and the resulting EXEC_FINISHED "
                                            + "event in your inbox is flagged with "
                                            + "EXEC_TIMEOUT. Unlike brain's exec_run "
                                            + "there is no extend/lease API on the foot — "
                                            + "pass the full intended timeout up front.")),
            "required", List.of("command"));

    private final ClientExecutorService executor;
    private final SessionService sessionService;

    @Override
    public String name() {
        return "client_exec_run";
    }

    @Override
    public String description() {
        return "Run a shell command on the user's machine (foot client) and "
                + "wait up to ~15s. If it finishes in time you get status + stdout "
                + "+ stderr; otherwise the response carries a job id and "
                + "status=RUNNING — follow up with client_exec_status. Logs "
                + "live at stdoutPath/stderrPath; page through them with bounded "
                + "follow-ups (head, tail, grep -m, sed -n 'A,Bp').";
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
    public java.util.Set<String> labels() {
        return java.util.Set.of("executive", "side-effect");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params) {
        Object rawCmd = params == null ? null : params.get("command");
        if (!(rawCmd instanceof String command) || command.isBlank()) {
            throw new IllegalArgumentException(
                    "'command' is required and must be a non-empty string");
        }
        long waitMs = DEFAULT_WAIT_MS;
        Object rawWait = params == null ? null : params.get("waitMs");
        if (rawWait instanceof Number n && n.longValue() >= 0) {
            waitMs = n.longValue();
        }
        java.time.Instant deadline = null;
        Object rawDeadline = params == null ? null : params.get("deadlineSeconds");
        if (rawDeadline instanceof Number d && d.longValue() > 0) {
            deadline = java.time.Instant.now().plusSeconds(d.longValue());
        }
        SessionService.BoundSession bind = sessionService.current();
        String sessionId = bind == null ? null : bind.sessionId();
        String projectId = bind == null ? null : bind.projectId();
        ClientExecJob job = executor.submit(command, sessionId, projectId, deadline);
        executor.waitFor(job, waitMs);
        return ClientExecJobRenderer.render(job);
    }
}
