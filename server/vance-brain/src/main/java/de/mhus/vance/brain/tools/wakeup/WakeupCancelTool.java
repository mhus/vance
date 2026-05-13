package de.mhus.vance.brain.tools.wakeup;

import de.mhus.vance.brain.wakeup.WakeupRegistry;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Cancels a previously-scheduled wakeup before it fires. Returns
 * {@code cancelled=true} when the timer was found and stopped,
 * {@code cancelled=false} when the correlationId is unknown for the
 * calling process or the wakeup already fired. Idempotent — safe to
 * call twice.
 */
@Component
@RequiredArgsConstructor
public class WakeupCancelTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "correlationId", Map.of(
                            "type", "string",
                            "description",
                            "correlationId returned by wakeup_in.")),
            "required", List.of("correlationId"));

    private final WakeupRegistry wakeupRegistry;

    @Override
    public String name() {
        return "wakeup_cancel";
    }

    @Override
    public String description() {
        return "Cancel a wakeup scheduled via wakeup_in. Scoped to the "
                + "calling process so foreign correlationIds are silently "
                + "ignored. Returns {cancelled: true|false}.";
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
            throw new ToolException("wakeup_cancel requires a process context");
        }
        Object raw = params == null ? null : params.get("correlationId");
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'correlationId' is required");
        }
        boolean cancelled = wakeupRegistry.cancel(processId, s.trim());
        return Map.of("cancelled", cancelled);
    }
}
