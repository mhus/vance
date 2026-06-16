package de.mhus.vance.brain.tools.hooks;

import de.mhus.vance.api.hooks.HookEventName;
import de.mhus.vance.brain.hooks.HookDef;
import de.mhus.vance.brain.hooks.HookSourceKeys;
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

    public static HookEventName parseEvent(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ToolException("'event' is required");
        }
        if (!HookEventName.isKnown(raw)) {
            throw new ToolException("Unknown event '" + raw + "' — see HookEventName");
        }
        return HookEventName.ofWire(raw);
    }

    /** Tool-friendly projection of a hook definition. */
    public Map<String, Object> shape(String tenantId, HookDef def) {
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

    public String sourceKeyFor(HookEventName event, String hookName) {
        return HookSourceKeys.sourceFor(event.wireName(), hookName);
    }
}
