package de.mhus.vance.brain.tools.builtins;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Returns the current time as an ISO-8601 string. Useful smoke test —
 * zero side-effects, one optional parameter, built-in primary.
 */
@Component
public class CurrentTimeTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "zone", Map.of(
                            "type", "string",
                            "description",
                                    "IANA zone id (e.g. 'Europe/Berlin'). "
                                            + "Defaults to UTC.")),
            "required", java.util.List.of());

    private final Clock clock;

    public CurrentTimeTool() {
        this(Clock.systemUTC());
    }

    CurrentTimeTool(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String name() {
        return "current_time";
    }

    @Override
    public String description() {
        return "Returns the current wall-clock time as an ISO-8601 string.";
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
        String zoneParam = params == null ? null : (String) params.get("zone");
        ZoneId zone;
        try {
            zone = (zoneParam == null || zoneParam.isBlank())
                    ? ZoneId.of("UTC") : ZoneId.of(zoneParam);
        } catch (RuntimeException e) {
            throw new ToolException("Unknown zone: '" + zoneParam + "'");
        }
        Instant now = clock.instant();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("iso", DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .format(now.atZone(zone)));
        out.put("epochSeconds", now.getEpochSecond());
        out.put("zone", zone.getId());
        return out;
    }
}
