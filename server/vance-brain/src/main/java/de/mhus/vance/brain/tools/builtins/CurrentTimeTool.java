package de.mhus.vance.brain.tools.builtins;

import de.mhus.vance.shared.settings.TimezoneResolver;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
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
                                            + "Defaults to the user's configured "
                                            + "timezone (UTC if none is set).")),
            "required", java.util.List.of());

    private final Clock clock;
    private final @Nullable TimezoneResolver timezoneResolver;

    // @Autowired disambiguates: two constructors (this + the Clock test
    // seam) mean Spring would otherwise fall back to a no-arg default,
    // which no longer exists.
    @Autowired
    public CurrentTimeTool(TimezoneResolver timezoneResolver) {
        this(Clock.systemUTC(), timezoneResolver);
    }

    CurrentTimeTool(Clock clock, @Nullable TimezoneResolver timezoneResolver) {
        this.clock = clock;
        this.timezoneResolver = timezoneResolver;
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
    public boolean contributesPrak() {
        // Wall-clock probe — never an insight.
        return false;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of("read-only");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String zoneParam = params == null ? null : (String) params.get("zone");
        ZoneId zone;
        if (zoneParam != null && !zoneParam.isBlank()) {
            try {
                zone = ZoneId.of(zoneParam);
            } catch (RuntimeException e) {
                throw new ToolException("Unknown zone: '" + zoneParam + "'");
            }
        } else {
            // No explicit zone → the caller's configured display timezone
            // (user → tenant cascade), defaulting to UTC.
            zone = timezoneResolver == null
                    ? ZoneId.of("UTC")
                    : timezoneResolver.zoneId(ctx.tenantId(), ctx.userId());
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
