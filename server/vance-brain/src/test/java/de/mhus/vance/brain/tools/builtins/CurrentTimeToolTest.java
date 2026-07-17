package de.mhus.vance.brain.tools.builtins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.settings.TimezoneResolver;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CurrentTimeToolTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-06-24T12:00:00Z"), ZoneOffset.UTC);

    private final TimezoneResolver timezoneResolver = mock(TimezoneResolver.class);
    private final CurrentTimeTool tool = new CurrentTimeTool(FIXED, timezoneResolver);

    private static ToolInvocationContext ctx() {
        return new ToolInvocationContext("t", "proj", "sess", "p-1", "alice");
    }

    @Test
    void invoke_noZoneParam_defaultsToUserTimezone() {
        when(timezoneResolver.zoneId("t", "alice")).thenReturn(ZoneId.of("Asia/Kolkata"));

        Map<String, Object> out = tool.invoke(Map.of(), ctx());

        assertThat(out.get("zone")).isEqualTo("Asia/Kolkata");
        assertThat(out.get("iso")).isEqualTo("2026-06-24T17:30:00+05:30");
        assertThat(out.get("epochSeconds")).isEqualTo(Instant.parse("2026-06-24T12:00:00Z").getEpochSecond());
    }

    @Test
    void invoke_explicitZoneParam_overridesUserTimezone() {
        Map<String, Object> out = tool.invoke(Map.of("zone", "America/New_York"), ctx());

        assertThat(out.get("zone")).isEqualTo("America/New_York");
        // -04:00 in June (EDT).
        assertThat(out.get("iso")).isEqualTo("2026-06-24T08:00:00-04:00");
    }

    @Test
    void invoke_invalidZoneParam_throws() {
        assertThatThrownBy(() -> tool.invoke(Map.of("zone", "Mars/Olympus"), ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Mars/Olympus");
    }

    @Test
    void invoke_nullResolver_fallsBackToUtc() {
        CurrentTimeTool utcTool = new CurrentTimeTool(FIXED, null);

        Map<String, Object> out = utcTool.invoke(Map.of(), ctx());

        assertThat(out.get("zone")).isEqualTo("UTC");
        assertThat(out.get("iso")).isEqualTo("2026-06-24T12:00:00Z");
    }
}
