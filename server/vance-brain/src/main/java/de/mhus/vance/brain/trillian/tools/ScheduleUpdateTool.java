package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.brain.trillian.TrillianScheduleStore;
import de.mhus.vance.brain.trillian.TrillianUserEngine;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Changes one field of an existing appointment. Omitted fields stay as they
 * are — the entry itself remains the single place where "when is this next"
 * is answered.
 */
@Component
@RequiredArgsConstructor
public class ScheduleUpdateTool implements Tool {

    private static final Map<String, Object> SCHEMA;

    static {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(
                "name",
                Map.of(
                        "type", "string",
                        "description", "The schedule entry to change."));
        properties.put(
                "label",
                Map.of(
                        "type", "string",
                        "description", "New one-liner for the self-check frame."));
        properties.put(
                "payload",
                Map.of(
                        "type", "string",
                        "description", "New instruction text."));
        properties.put(
                "due",
                Map.of(
                        "type", "string",
                        "description", "New ISO-8601 next fire."));
        properties.put(
                "every",
                Map.of(
                        "type", "string",
                        "description", "New recurrence (30m, 2h, 1d; minimum 5m). Empty string clears it."));
        properties.put(
                "enabled",
                Map.of(
                        "type", "boolean",
                        "description", "false parks the entry without deleting it."));
        SCHEMA = Map.of("type", "object", "properties", properties, "required", List.of("name"));
    }

    private final TrillianScheduleStore scheduleStore;
    private final de.mhus.vance.brain.trillian.TrillianAgendaService agendaService;

    @Override
    public String name() {
        return "schedule_update";
    }

    @Override
    public String description() {
        return "Change one field of an existing appointment (label, payload, "
                + "due, every, enabled). Fields you omit stay as they are.";
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
    public Set<String> requiresEngineRoles() {
        return Set.of(TrillianUserEngine.ROLE_TRILLIAN_USER);
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Object rawName = params == null ? null : params.get("name");
        if (!(rawName instanceof String name) || name.isBlank()) {
            throw new ToolException("'name' is required");
        }
        String key = name.trim();
        TrillianScheduleStore.Schedule current = scheduleStore
                .find(ctx.tenantId(), ctx.projectId(), key)
                .orElseThrow(() -> new ToolException("schedule_update: no entry named '" + key + "'"));

        String label = orCurrent(stringOrNull(params, "label"), current.label());
        String payload = orCurrent(stringOrNull(params, "payload"), current.payload());
        String next = params != null && params.containsKey("every")
                ? emptyToNull(stringOrNull(params, "every"))
                : current.next();
        boolean enabled = params != null && params.get("enabled") instanceof Boolean b ? b : current.enabled();
        Instant due = current.due();
        String dueRaw = stringOrNull(params, "due");
        try {
            if (dueRaw != null) {
                due = Instant.parse(dueRaw.trim());
            } else if (params != null && params.containsKey("every") && next != null) {
                due = TrillianScheduleStore.nextDue(next, Instant.now());
            }
        } catch (RuntimeException e) {
            throw new ToolException("schedule_update: bad due/every — " + e.getMessage(), e);
        }

        try {
            scheduleStore.save(
                    ctx.tenantId(),
                    ctx.projectId(),
                    new TrillianScheduleStore.Schedule(key, label, due, next, payload, enabled, current.lastRun()));
        } catch (IllegalArgumentException e) {
            throw new ToolException("schedule_update: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new ToolException("schedule_update: could not store '" + key + "' — " + e.getMessage(), e);
        }
        agendaService.refreshScheduleMarker(ctx.processId());
        return Map.of("name", key, "due", due.toString(), "enabled", enabled);
    }

    private static @Nullable String orCurrent(@Nullable String value, @Nullable String current) {
        return value == null ? current : value;
    }

    private static @Nullable String emptyToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static @Nullable String stringOrNull(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
