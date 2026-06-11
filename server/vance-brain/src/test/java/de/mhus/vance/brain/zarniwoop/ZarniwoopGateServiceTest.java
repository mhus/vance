package de.mhus.vance.brain.zarniwoop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.project.ProjectEnginesStopRequested;
import de.mhus.vance.brain.zarniwoop.ZarniwoopGateService.ManualState;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.toolpack.research.SearchScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ZarniwoopGateServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "alpha";
    private static final String INSTANCE = "serper-main";
    private static final SearchScope SCOPE = SearchScope.of(TENANT, PROJECT);

    private SettingService settings;
    private ZarniwoopGateService gate;

    @BeforeEach
    void setUp() {
        settings = mock(SettingService.class);
        gate = new ZarniwoopGateService(settings);
    }

    @Test
    void default_isEnabled_true_when_no_setting() {
        when(settings.getStringValueCascade(eq(TENANT), eq(PROJECT), any(), any()))
                .thenReturn(null);

        assertThat(gate.isEnabled(SCOPE, INSTANCE)).isTrue();
        assertThat(gate.resolve(SCOPE, INSTANCE).defaultEnabled()).isTrue();
        assertThat(gate.resolve(SCOPE, INSTANCE).override()).isEmpty();
    }

    @Test
    void settings_false_disables_instance() {
        when(settings.getStringValueCascade(
                eq(TENANT), eq(PROJECT), any(),
                eq(ZarniwoopSettings.endpointEnabledKey(INSTANCE))))
                .thenReturn("false");

        assertThat(gate.isEnabled(SCOPE, INSTANCE)).isFalse();
        assertThat(gate.resolve(SCOPE, INSTANCE).defaultEnabled()).isFalse();
    }

    @Test
    void override_enabled_wins_over_settings_false() {
        when(settings.getStringValueCascade(
                eq(TENANT), eq(PROJECT), any(),
                eq(ZarniwoopSettings.endpointEnabledKey(INSTANCE))))
                .thenReturn("false");

        gate.setOverride(SCOPE, INSTANCE, ManualState.ENABLED);

        assertThat(gate.isEnabled(SCOPE, INSTANCE)).isTrue();
        ZarniwoopGateService.GateDecision d = gate.resolve(SCOPE, INSTANCE);
        assertThat(d.defaultEnabled()).isFalse();
        assertThat(d.override()).contains(ManualState.ENABLED);
        assertThat(d.effectivelyEnabled()).isTrue();
    }

    @Test
    void override_disabled_wins_over_settings_true() {
        when(settings.getStringValueCascade(any(), any(), any(), any())).thenReturn(null);
        gate.setOverride(SCOPE, INSTANCE, ManualState.DISABLED);
        assertThat(gate.isEnabled(SCOPE, INSTANCE)).isFalse();
    }

    @Test
    void clearOverride_returns_to_settings_default() {
        when(settings.getStringValueCascade(any(), any(), any(), any())).thenReturn(null);
        gate.setOverride(SCOPE, INSTANCE, ManualState.DISABLED);
        assertThat(gate.isEnabled(SCOPE, INSTANCE)).isFalse();

        gate.clearOverride(SCOPE, INSTANCE);

        assertThat(gate.isEnabled(SCOPE, INSTANCE)).isTrue();
        assertThat(gate.resolve(SCOPE, INSTANCE).override()).isEmpty();
    }

    @Test
    void project_stop_clears_overrides() {
        when(settings.getStringValueCascade(any(), any(), any(), any())).thenReturn(null);
        gate.setOverride(SCOPE, INSTANCE, ManualState.DISABLED);
        SearchScope otherProject = SearchScope.of(TENANT, "other");
        gate.setOverride(otherProject, INSTANCE, ManualState.DISABLED);

        gate.onProjectStop(new ProjectEnginesStopRequested(TENANT, PROJECT));

        assertThat(gate.currentOverride(SCOPE, INSTANCE)).isEmpty();
        assertThat(gate.currentOverride(otherProject, INSTANCE))
                .contains(ManualState.DISABLED);
    }

    @Test
    void setOverride_rejects_missing_project_or_instance() {
        when(settings.getStringValueCascade(any(), any(), any(), any())).thenReturn(null);
        SearchScope noProject = new SearchScope(TENANT, "", null, null);
        assertThatThrownBy(() ->
                gate.setOverride(noProject, INSTANCE, ManualState.DISABLED))
                .isInstanceOf(ZarniwoopException.class);
        assertThatThrownBy(() ->
                gate.setOverride(SCOPE, "", ManualState.DISABLED))
                .isInstanceOf(ZarniwoopException.class);
    }
}
