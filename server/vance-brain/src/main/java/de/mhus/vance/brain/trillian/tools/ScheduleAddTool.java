package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.brain.trillian.TrillianScheduleStore;
import de.mhus.vance.brain.trillian.TrillianUserEngine;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Writes an appointment into the loop's own schedule — the durable form of
 * {@code wakeup_in}. Upsert semantics: re-adding a name replaces the entry.
 */
@Component
@RequiredArgsConstructor
public class ScheduleAddTool implements Tool {

    private static final Map<String, Object> SCHEMA;

    static {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(
                "name",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Stable entry name, unique per Trillian: lowercase letters, digits, '-' and '_'"
                                + " (e.g. 'morning-briefing')."));
        properties.put(
                "payload",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "What to do when it comes up. Self-contained — your future "
                                + "self sees this and the label, not this conversation."));
        properties.put(
                "due",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "ISO-8601 instant of the next fire. Defaults to now + every; "
                                + "required when no every is given."));
        properties.put(
                "every",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Recurrence like 30m, 2h, 1d (minimum 5m). The next due is "
                                + "computed from the fire time, so missed runs never accumulate. "
                                + "Without it the entry is a one-shot."));
        properties.put(
                "label",
                Map.of(
                        "type", "string",
                        "description", "Optional one-liner shown in the self-check frame."));
        SCHEMA = Map.of("type", "object", "properties", properties, "required", List.of("name", "payload"));
    }

    private final TrillianScheduleStore scheduleStore;
    private final de.mhus.vance.brain.trillian.TrillianAgendaService agendaService;

    @Override
    public String name() {
        return "schedule_add";
    }

    @Override
    public String description() {
        return "Put an appointment on your own schedule — the durable form "
                + "of a reminder: it survives restarts and comes back as a "
                + "self-check item when due. One-shot (due only) or repeating "
                + "(every >= 5m). Re-adding the same name replaces the entry.";
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
        return Set.of(ToolLabels.INTERNAL, "executive");
    }

    @Override
    public Set<String> requiresEngineRoles() {
        return Set.of(TrillianUserEngine.ROLE_TRILLIAN_USER);
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String name;
        try {
            name = TrillianScheduleStore.requireValidName(stringOrThrow(params, "name"));
        } catch (IllegalArgumentException e) {
            throw new ToolException("schedule_add: " + e.getMessage(), e);
        }
        String payload = stringOrThrow(params, "payload");
        String every = stringOrNull(params, "every");
        String label = stringOrNull(params, "label");
        Instant due;
        try {
            String dueRaw = stringOrNull(params, "due");
            due = dueRaw == null
                    ? (every == null ? null : TrillianScheduleStore.nextDue(every, Instant.now()))
                    : Instant.parse(dueRaw.trim());
        } catch (RuntimeException e) {
            throw new ToolException("schedule_add: bad due/every — " + e.getMessage(), e);
        }
        if (due == null) {
            throw new ToolException("schedule_add: either 'due' or 'every' is required");
        }
        TrillianScheduleStore.Schedule schedule =
                new TrillianScheduleStore.Schedule(name, label, due, every, payload, true, null);
        try {
            scheduleStore.save(ctx.tenantId(), ctx.projectId(), schedule);
        } catch (IllegalArgumentException e) {
            throw new ToolException("schedule_add: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new ToolException("schedule_add: could not store '" + name + "' — " + e.getMessage(), e);
        }
        agendaService.refreshScheduleMarker(ctx.processId());
        return Map.of("name", name, "due", due.toString(), "next", every == null ? "" : every, "status", "scheduled");
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required");
        }
        return s.trim();
    }

    private static @Nullable String stringOrNull(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
