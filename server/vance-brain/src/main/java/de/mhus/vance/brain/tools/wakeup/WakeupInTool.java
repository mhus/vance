package de.mhus.vance.brain.tools.wakeup;

import de.mhus.vance.brain.wakeup.WakeupRegistry;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Schedules a delayed self-wakeup. The calling process receives a
 * {@code ProcessEvent(SCHEDULED_WAKEUP)} in its inbox after
 * {@code seconds}, carrying back the {@code label} and (optional)
 * {@code payload}.
 *
 * <p>Used to build heartbeats, polling loops, and watchdogs for
 * long-running operations — see {@code planning/wakeup-and-exec.md}.
 */
@Component
@RequiredArgsConstructor
public class WakeupInTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "seconds", Map.of(
                            "type", "integer",
                            "description",
                            "Delay until the wakeup fires. Must be positive. "
                                    + "Wall-clock seconds — pauses on the process do not stop the timer."),
                    "label", Map.of(
                            "type", "string",
                            "description",
                            "Short human-readable hint shown in logs and on the inbox event. "
                                    + "Include enough context to recognise what the wakeup is about "
                                    + "(e.g. 'check long-build #j-abc')."),
                    "payload", Map.of(
                            "type", "object",
                            "description",
                            "Optional structured data echoed back on the wakeup event. "
                                    + "Use to carry job ids, target document refs, or whatever "
                                    + "state the follow-up turn needs.")),
            "required", List.of("seconds", "label"));

    private final WakeupRegistry wakeupRegistry;

    @Override
    public String name() {
        return "wakeup_in";
    }

    @Override
    public String description() {
        return "Schedule a self-wakeup. After 'seconds' the calling process "
                + "receives a SCHEDULED_WAKEUP event in its inbox with the "
                + "given label and optional payload. Returns a correlationId "
                + "you can pass to wakeup_cancel to revoke before it fires. "
                + "\n"
                + "Heartbeat pattern for long-running exec jobs: pair with "
                + "work_exec_run(deadlineSeconds=N) and work_exec_check. On each wakeup, "
                + "call work_exec_check(jobId, ifRunning='extend', extendSeconds=N) "
                + "to push the lease out, then queue the next wakeup_in(N/2, "
                + "...). If you forget a heartbeat the watchdog kills the job "
                + "and emits EXEC_TIMEOUT — the lease is your safety net.";
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
        String processId = ctx == null ? null : ctx.processId();
        if (processId == null || processId.isBlank()) {
            throw new ToolException("wakeup_in requires a process context");
        }
        int seconds = intParam(params, "seconds");
        if (seconds <= 0) {
            throw new ToolException("'seconds' must be a positive integer");
        }
        String label = stringParam(params, "label");
        if (label.isBlank()) {
            throw new ToolException("'label' is required");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = params != null && params.get("payload") instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw
                : null;

        String correlationId = wakeupRegistry.schedule(
                processId, Duration.ofSeconds(seconds), label, payload);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("correlationId", correlationId);
        out.put("seconds", seconds);
        out.put("label", label);
        return out;
    }

    private static int intParam(Map<String, Object> params, String key) {
        if (params == null) return -1;
        Object v = params.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignore) {
                return -1;
            }
        }
        return -1;
    }

    private static String stringParam(Map<String, Object> params, String key) {
        if (params == null) return "";
        Object v = params.get(key);
        return v instanceof String s ? s.trim() : "";
    }
}
