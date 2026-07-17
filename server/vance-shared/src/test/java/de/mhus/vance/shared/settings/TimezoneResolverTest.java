package de.mhus.vance.shared.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.home.HomeBootstrapService;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * Verifies the timezone cascade (user → tenant → UTC), IANA validation
 * and the null-user fallback. The cascade storage itself is owned by
 * {@link SettingService}; we only check delegation + defaulting.
 */
class TimezoneResolverTest {

    private final SettingService settingService = mock(SettingService.class);
    private final TimezoneResolver resolver = new TimezoneResolver(settingService);

    @Test
    void findTimezone_usesUserThenTenantCascade() {
        when(settingService.getUserStringValueWithDefault(
                "t", "alice", TimezoneResolver.Keys.DISPLAY_TIMEZONE))
                .thenReturn("Asia/Kolkata");

        assertThat(resolver.findTimezone("t", "alice")).isEqualTo("Asia/Kolkata");
    }

    @Test
    void zoneId_returnsParsedZone_whenConfigured() {
        when(settingService.getUserStringValueWithDefault(
                "t", "alice", TimezoneResolver.Keys.DISPLAY_TIMEZONE))
                .thenReturn("Europe/Berlin");

        assertThat(resolver.zoneId("t", "alice")).isEqualTo(ZoneId.of("Europe/Berlin"));
    }

    @Test
    void zoneId_defaultsToUtc_whenNothingConfigured() {
        when(settingService.getUserStringValueWithDefault(
                "t", "alice", TimezoneResolver.Keys.DISPLAY_TIMEZONE))
                .thenReturn(null);

        assertThat(resolver.zoneId("t", "alice")).isEqualTo(ZoneId.of("UTC"));
        assertThat(resolver.findZoneId("t", "alice")).isNull();
    }

    @Test
    void findZoneId_returnsNull_forInvalidZone() {
        when(settingService.getUserStringValueWithDefault(
                "t", "alice", TimezoneResolver.Keys.DISPLAY_TIMEZONE))
                .thenReturn("Mars/Olympus");

        assertThat(resolver.findZoneId("t", "alice")).isNull();
        assertThat(resolver.zoneId("t", "alice")).isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    void findTimezone_nullUser_readsTenantLayerDirectly() {
        when(settingService.getStringValue(
                "t", SettingService.SCOPE_PROJECT,
                HomeBootstrapService.TENANT_PROJECT_NAME,
                TimezoneResolver.Keys.DISPLAY_TIMEZONE))
                .thenReturn("Australia/Sydney");

        assertThat(resolver.findTimezone("t", null)).isEqualTo("Australia/Sydney");
        assertThat(resolver.zoneId("t", null)).isEqualTo(ZoneId.of("Australia/Sydney"));
    }
}
