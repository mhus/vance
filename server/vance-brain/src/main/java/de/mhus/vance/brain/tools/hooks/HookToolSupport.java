package de.mhus.vance.brain.tools.hooks;

import de.mhus.vance.api.ursahooks.UrsaHookEventName;
import de.mhus.vance.brain.ursahooks.UrsaHookDef;
import de.mhus.vance.brain.ursahooks.UrsaHookSourceKeys;
import de.mhus.vance.shared.eventlog.EventLogDocument;
import de.mhus.vance.shared.eventlog.EventLogService;
import de.mhus.vance.toolpack.ToolException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Common helpers for the hook agent tools — name validation, event
 * parsing, projection-into-map for tool results.
 */
@Component
@RequiredArgsConstructor
public class HookToolSupport {

    private static final Pattern NAME_PATTERN =
            Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    private final EventLogService eventLogService;
    private final de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;
    private final de.mhus.vance.shared.permission.PermissionService permissionService;

    /**
     * Authorize + build the write actor for a hook-tool document write. Hook
     * YAML lives under the server-owned {@code _vance/hooks/} namespace, which
     * is SYSTEM-only at the document chokepoint (a plain user-actor write is
     * denied regardless of role). As the dedicated authoring tool for that
     * namespace this support owns the policy: it enforces project-ADMIN here,
     * then writes as a trusted SYSTEM operation with the caller's real subject
     * kept for audit. A non-admin therefore cannot plant a (possibly
     * {@code runAs}-carrying) hook; a headless caller (null userId → SYSTEM
     * subject) passes the ADMIN gate as an internal actor.
     */
    public de.mhus.vance.shared.permission.WriteActor adminSystemActor(
            String tenantId, String projectId, @org.jspecify.annotations.Nullable String userId) {
        de.mhus.vance.shared.permission.SecurityContext subject =
                contextFactory.forToolSubject(tenantId, userId);
        permissionService.enforce(subject,
                new de.mhus.vance.shared.permission.Resource.Project(tenantId, projectId),
                de.mhus.vance.shared.permission.Action.ADMIN);
        return de.mhus.vance.shared.permission.WriteActor.system(subject);
    }

    /** Normalise + validate a hook name passed by the agent. */
    public static String normalizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ToolException("'name' is required");
        }
        String norm = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (!NAME_PATTERN.matcher(norm).matches()) {
            throw new ToolException(
                    "name '" + raw + "' must match [a-z0-9][a-z0-9_-]{0,63}");
        }
        return norm;
    }

    public static UrsaHookEventName parseEvent(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ToolException("'event' is required");
        }
        if (!UrsaHookEventName.isKnown(raw)) {
            throw new ToolException("Unknown event '" + raw + "' — see UrsaHookEventName");
        }
        return UrsaHookEventName.ofWire(raw);
    }

    /** Tool-friendly projection of a hook definition. */
    public Map<String, Object> shape(String tenantId, UrsaHookDef def) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", def.name());
        m.put("event", def.event().wireName());
        m.put("actionType", def.actionType());
        m.put("enabled", def.enabled());
        m.put("source", def.source().name().toLowerCase(java.util.Locale.ROOT));
        if (def.description() != null) m.put("description", def.description());
        m.put("timeoutMs", def.timeout().toMillis());
        if (def.tags() != null && !def.tags().isEmpty()) {
            m.put("tags", List.copyOf(def.tags()));
        }
        // Last-run summary from the event log.
        Optional<EventLogDocument> last = eventLogService.findLatest(
                tenantId, def.sourceKey(),
                List.of(de.mhus.vance.api.eventlog.EventType.COMPLETED,
                        de.mhus.vance.api.eventlog.EventType.FAILED,
                        de.mhus.vance.api.eventlog.EventType.SKIPPED));
        if (last.isPresent()) {
            m.put("lastRunAt", last.get().getTimestamp().toString());
            m.put("lastRunType", last.get().getType().name());
        }
        return m;
    }

    public String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }

    public String sourceKeyFor(UrsaHookEventName event, String hookName) {
        return UrsaHookSourceKeys.sourceFor(event.wireName(), hookName);
    }
}
