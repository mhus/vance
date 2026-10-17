package de.mhus.vance.shared.llmusage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link LlmPowerService}: only "off" is stored, always in {@code _tenant}. */
class LlmPowerServiceTest {

    private static final String TENANT = "acme";

    private final SettingService settingService = mock(SettingService.class);
    private final LlmPowerService service = new LlmPowerService(settingService);

    @Test
    void load_noSettings_readsAllOn() {
        when(settingService.findByPrefix(TENANT, "project", "_tenant", "llm.power."))
                .thenReturn(List.of());

        LlmPowerState state = service.load(TENANT);

        assertThat(state.tenantOn()).isTrue();
        assertThat(state.usersOff()).isEmpty();
    }

    @Test
    void load_offKeys_readAsTenantAndUsersOff_withTimeAndNote() {
        Instant at = Instant.parse("2026-10-17T10:00:00Z");
        when(settingService.findByPrefix(TENANT, "project", "_tenant", "llm.power."))
                .thenReturn(List.of(
                        setting("llm.power.enabled", "false", at, "Switched off by marvin"),
                        setting("llm.power.user.road.runner", "false", at, null),
                        setting("llm.power.user.wile.coyote", "true", at, null),
                        setting("llm.power.user.", "false", at, null)));

        LlmPowerState state = service.load(TENANT);

        assertThat(state.tenantOn()).isFalse();
        assertThat(state.tenantOff()).isEqualTo(new LlmPowerState.Switch(at, "Switched off by marvin"));
        assertThat(state.usersOff()).containsOnlyKeys("road.runner");
        assertThat(state.userOn("road.runner")).isFalse();
        assertThat(state.userOn("wile.coyote")).isTrue();
    }

    @Test
    void setUserPower_off_writesFalseIntoTenantProject_withActor() {
        service.setUserPower(TENANT, "road.runner", false, "marvin");

        verify(settingService)
                .setAs(
                        TENANT,
                        "project",
                        "_tenant",
                        "llm.power.user.road.runner",
                        "false",
                        SettingType.BOOLEAN,
                        "Switched off by marvin",
                        "marvin");
    }

    @Test
    void setTenantPower_on_deletesTheKey_andWritesNothing() {
        service.setTenantPower(TENANT, true, "marvin");

        verify(settingService).delete(TENANT, "project", "_tenant", "llm.power.enabled");
        verify(settingService, never()).setAs(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void setUserPower_blankUser_isRejected() {
        assertThatThrownBy(() -> service.setUserPower(TENANT, " ", false, "marvin"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(settingService, never()).delete(anyString(), anyString(), anyString(), anyString());
    }

    private static SettingDocument setting(String key, String value, Instant at, String description) {
        SettingDocument doc = new SettingDocument();
        doc.setKey(key);
        doc.setValue(value);
        doc.setUpdatedAt(at);
        doc.setDescription(description);
        return doc;
    }
}
