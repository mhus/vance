package de.mhus.vance.brain.tools.exec;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Force-decision watchdog check on a long-running exec job. Unlike
 * {@code exec_status} (passive read), this tool requires the LLM to
 * commit to an action in case the job is still running:
 *
 * <ul>
 *   <li><b>extend</b> — push out the deadline by {@code extendSeconds}.
 *       Use this as your heartbeat for long jobs.</li>
 *   <li><b>kill</b> — terminate the subprocess now.</li>
 *   <li><b>wait</b> — explicit "I'm only observing, leave it be".
 *       Not a default: choosing this is a conscious decision, not
 *       a sneak peek without consequences.</li>
 * </ul>
 *
 * When the job is already terminal, the {@code ifRunning} verb is
 * ignored — the response carries the final status and output the same
 * way {@code exec_status} would, plus {@code terminal: true}.
 *
 * <p>Plan: {@code planning/wakeup-and-exec.md} §4.2.
 */
@Component
@RequiredArgsConstructor
public class ExecCheckTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "id", Map.of(
                            "type", "string",
                            "description", "Job id returned by exec_run."),
                    "ifRunning", Map.of(
                            "type", "string",
                            "enum", List.of("extend", "kill", "wait"),
                            "description",
                                    "Force-decision verb that applies only when the "
                                            + "job is still RUNNING. 'extend' pushes "
                                            + "the deadline (requires extendSeconds), "
                                            + "'kill' terminates, 'wait' is a no-op "
                                            + "observation. Pick consciously — there "
                                            + "is no default."),
                    "extendSeconds", Map.of(
                            "type", "integer",
                            "description",
                                    "Required when ifRunning='extend'. New lease "
                                            + "length from now, in seconds.")),
            "required", List.of("id", "ifRunning"));

    private final ExecManager execManager;
    private final ExecProperties properties;

    @Override
    public String name() {
        return "exec_check";
    }

    @Override
    public String description() {
        return "Check on a running exec job and decide what to do in one "
                + "atomic step. Use as the heartbeat for long-running jobs "
                + "started with deadlineSeconds — pair with wakeup_in to "
                + "wake yourself up periodically and extend the lease. "
                + "Returns the same fields as exec_status plus 'decision' "
                + "and (on extend) 'newDeadline'.";
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
        return Set.of("executive");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Object rawId = params == null ? null : params.get("id");
        if (!(rawId instanceof String id) || id.isBlank()) {
            throw new ToolException("'id' is required");
        }
        Object rawIf = params == null ? null : params.get("ifRunning");
        if (!(rawIf instanceof String ifRunning) || ifRunning.isBlank()) {
            throw new ToolException(
                    "'ifRunning' is required (one of: extend, kill, wait)");
        }
        String decision = ifRunning.trim().toLowerCase();
        if (!decision.equals("extend")
                && !decision.equals("kill")
                && !decision.equals("wait")) {
            throw new ToolException(
                    "'ifRunning' must be one of: extend, kill, wait (got '"
                            + ifRunning + "')");
        }

        ExecJob job = execManager.get(ctx.tenantId(), ctx.projectId(), id)
                .orElseThrow(() -> new ToolException(
                        "Unknown exec job: '" + id + "' (not in this project)"));

        // Terminal jobs: 'ifRunning' is moot, surface the final state.
        if (job.isTerminal()) {
            Map<String, Object> out = new LinkedHashMap<>(
                    ExecJobRenderer.render(job, properties.getInlineOutputCharCap()));
            out.put("decision", decision);
            out.put("terminal", true);
            return out;
        }

        return switch (decision) {
            case "extend" -> doExtend(ctx, job, params);
            case "kill" -> doKill(ctx, job);
            case "wait" -> doWait(job);
            default -> throw new ToolException("unreachable");
        };
    }

    private Map<String, Object> doExtend(
            ToolInvocationContext ctx, ExecJob job, Map<String, Object> params) {
        Object rawSec = params.get("extendSeconds");
        long extendSeconds = rawSec instanceof Number n ? n.longValue() : -1L;
        if (extendSeconds <= 0) {
            throw new ToolException(
                    "'extendSeconds' must be a positive integer when ifRunning='extend'");
        }
        boolean extended = execManager.extendDeadline(
                ctx.tenantId(), ctx.projectId(), job.id(),
                Duration.ofSeconds(extendSeconds));
        Map<String, Object> out = new LinkedHashMap<>(
                ExecJobRenderer.render(job, properties.getInlineOutputCharCap()));
        out.put("decision", "extend");
        out.put("terminal", false);
        if (extended) {
            Instant newDeadline = job.deadline();
            if (newDeadline != null) {
                out.put("newDeadline", newDeadline.toString());
            }
        } else {
            // Race: the job terminated between the isTerminal() check above
            // and the extendDeadline call. Re-render so the LLM sees the
            // terminal status it must have raced against.
            out.put("terminal", true);
            out.put("extendApplied", false);
        }
        return out;
    }

    private Map<String, Object> doKill(ToolInvocationContext ctx, ExecJob job) {
        boolean killed = execManager.kill(ctx.tenantId(), ctx.projectId(), job.id());
        Map<String, Object> out = new LinkedHashMap<>(
                ExecJobRenderer.render(job, properties.getInlineOutputCharCap()));
        out.put("decision", "kill");
        out.put("killApplied", killed);
        out.put("terminal", job.isTerminal());
        return out;
    }

    private Map<String, Object> doWait(ExecJob job) {
        Map<String, Object> out = new LinkedHashMap<>(
                ExecJobRenderer.render(job, properties.getInlineOutputCharCap()));
        out.put("decision", "wait");
        out.put("terminal", false);
        Instant deadline = job.deadline();
        if (deadline != null) {
            out.put("deadline", deadline.toString());
        }
        return out;
    }
}
