package de.mhus.vance.brain.tools.ursascheduler;

import de.mhus.vance.brain.ursascheduler.SchedulerLogService;
import de.mhus.vance.brain.ursascheduler.UrsaSchedulerService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Trigger a registered scheduler immediately, bypassing its cron
 * schedule — primarily for end-to-end verification ("does the recipe
 * actually run when fired?") without having to wait for the next
 * natural tick.
 *
 * <p>Goes through the exact same code path as a cron tick: overlap
 * policy applies, event-log + scheduler-log document + metrics all
 * fire identically. The only distinction is the {@code trigger=manual}
 * marker on the scheduler-log document so the run is later
 * distinguishable from cron-driven ones.
 *
 * <p>Result carries the {@code correlationId} and the {@code logPath}
 * of the matching scheduler-log document — read it via
 * {@code document_read} to see how the run progressed.
 */
@Component
@RequiredArgsConstructor
public class UrsaSchedulerFireTool implements Tool {

    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", Map.of(
                "type", "string",
                "description", "Scheduler name (without .yaml suffix)."));
        SCHEMA = Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("name"));
    }

    private final UrsaSchedulerService schedulerService;

    @Override public String name() { return "scheduler_fire"; }

    @Override public String description() {
        return "Trigger a scheduler immediately, bypassing its cron schedule. "
                + "Returns the correlationId and the path of the scheduler-log "
                + "document — read it with document_read to see whether the run "
                + "succeeded, failed, or is still pending. Overlap policy applies "
                + "as for a cron tick; the run is marked trigger=manual.";
    }

    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("admin", "scheduler"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.projectId() == null) {
            throw new ToolException("scheduler_fire requires a project scope");
        }
        String name = UrsaSchedulerToolSupport.normalizeName(stringOrThrow(params, "name"));
        UrsaSchedulerService.FireOutcome outcome;
        try {
            outcome = schedulerService.fireNow(ctx.tenantId(), ctx.projectId(), name);
        } catch (IllegalArgumentException ex) {
            throw new ToolException(ex.getMessage());
        }
        // Path is computed from the same firedAt the writer uses, so
        // the document is guaranteed to exist at exactly this path
        // (no second-boundary race between the two Instant.now() calls).
        String logPath = SchedulerLogService.pathFor(name, outcome.firedAt(), outcome.correlationId());
        return Map.of(
                "correlationId", outcome.correlationId(),
                "logPath", logPath,
                "note", "Run started. Read '" + logPath + "' via document_read for status/outcome.");
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }
}
