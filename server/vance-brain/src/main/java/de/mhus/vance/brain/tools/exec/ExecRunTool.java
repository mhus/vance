package de.mhus.vance.brain.tools.exec;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Runs a shell command in the session workspace. Blocks briefly so
 * short commands return synchronously; longer ones come back still
 * RUNNING and the LLM follows up with {@code exec_status} by id.
 */
@Component
@RequiredArgsConstructor
public class ExecRunTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "command", Map.of(
                            "type", "string",
                            "description",
                                    "Shell command to run (bash via /bin/sh -c on "
                                            + "Linux/macOS, cmd.exe /c on Windows). "
                                            + "Full shell syntax allowed; cwd is the "
                                            + "session workspace."),
                    "waitMs", Map.of(
                            "type", "integer",
                            "description",
                                    "Milliseconds to wait for completion before "
                                            + "returning. Default is the server's "
                                            + "configured wait.")),
            "required", List.of("command"));

    private final ExecManager execManager;
    private final ExecProperties properties;

    @Override
    public String name() {
        return "exec_run";
    }

    @Override
    public String description() {
        return "Execute a shell command in the session workspace and wait up "
                + "to ~15s for completion. If it finishes in time you get "
                + "status + stdout + stderr; otherwise you get the job id and "
                + "follow up with exec_status. stdoutPath / stderrPath are the "
                + "complete log files on disk — page through them with bounded "
                + "further exec_run calls (head, tail, grep -m, sed -n 'A,Bp').";
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
        Object rawCmd = params == null ? null : params.get("command");
        if (!(rawCmd instanceof String command) || command.isBlank()) {
            throw new ToolException("'command' is required and must be a non-empty string");
        }
        long waitMs = properties.getDefaultWaitMs();
        Object rawWait = params == null ? null : params.get("waitMs");
        if (rawWait instanceof Number n && n.longValue() >= 0) {
            waitMs = n.longValue();
        }
        try {
            ExecJob job = execManager.submit(ctx.sessionId(), command);
            execManager.waitFor(job, waitMs);
            return ExecJobRenderer.render(job, properties.getInlineOutputCharCap());
        } catch (ExecException e) {
            throw new ToolException(e.getMessage(), e);
        }
    }
}
