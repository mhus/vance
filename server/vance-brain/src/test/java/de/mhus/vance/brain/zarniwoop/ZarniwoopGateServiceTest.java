package de.mhus.vance.brain.zarniwoop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.project.ProjectEnginesStopRequested;
import de.mhus.vance.brain.zarniwoop.ZarniwoopGateService.ManualState;
import de.mhus.vance.brain.sourceconfig.SourceConfig;
import java.util.Map;
import de.mhus.vance.brain.sourceconfig.SourceConfigPaths;
import de.mhus.vance.toolpack.research.SearchScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ZarniwoopGateServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "alpha";
    private static final String INSTANCE = "serper-main";
    private static final SearchScope SCOPE = SearchScope.of(TENANT, PROJECT);

    private SearchProviderFactory providerFactory;
    private ZarniwoopGateService gate;

    @BeforeEach
    void setUp() {
        providerFactory = mock(SearchProviderFactory.class);
        gate = new ZarniwoopGateService(providerFactory);
    }

    /** The endpoint is configured and says nothing about enabled. */
    private void givenConfigured() {
        givenConfigured(true);
    }

    private void givenConfigured(boolean enabled) {
        when(providerFactory.config(any(), any())).thenReturn(new SourceConfig(
                INSTANCE, SourceConfigPaths.pathFor(SourceConfigPaths.RESEARCH, INSTANCE),
                "serper", "https://google.serper.dev", null, enabled, Map.of()));
    }

    @Test
    void default_isEnabled_true_when_documentSaysNothing() {
        givenConfigured();

        assertThat(gate.isEnabled(SCOPE, INSTANCE)).isTrue();
        assertThat(gate.resolve(SCOPE, INSTANCE).defaultEnabled()).isTrue();
        assertThat(gate.resolve(SCOPE, INSTANCE).override()).isEmpty();
    }

    @Test
    void configured_false_disables_instance() {
        givenConfigured(false);

        assertThat(gate.isEnabled(SCOPE, INSTANCE)).isFalse();
        assertThat(gate.resolve(SCOPE, INSTANCE).defaultEnabled()).isFalse();
    }

    @Test
    void override_enabled_wins_over_configured_false() {
        givenConfigured(false);

        gate.setOverride(SCOPE, INSTANCE, ManualState.ENABLED);

        assertThat(gate.isEnabled(SCOPE, INSTANCE)).isTrue();
        ZarniwoopGateService.GateDecision d = gate.resolve(SCOPE, INSTANCE);
        assertThat(d.defaultEnabled()).isFalse();
        assertThat(d.override()).contains(ManualState.ENABLED);
        assertThat(d.effectivelyEnabled()).isTrue();
    }

    @Test
    void override_disabled_wins_over_configured_true() {
        givenConfigured();
        gate.setOverride(SCOPE, INSTANCE, ManualState.DISABLED);
        assertThat(gate.isEnabled(SCOPE, INSTANCE)).isFalse();
    }

    @Test
    void clearOverride_returns_to_configured_default() {
        givenConfigured();
        gate.setOverride(SCOPE, INSTANCE, ManualState.DISABLED);
        assertThat(gate.isEnabled(SCOPE, INSTANCE)).isFalse();

        gate.clearOverride(SCOPE, INSTANCE);

        assertThat(gate.isEnabled(SCOPE, INSTANCE)).isTrue();
        assertThat(gate.resolve(SCOPE, INSTANCE).override()).isEmpty();
    }

    @Test
    void project_stop_clears_overrides() {
        givenConfigured();
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
        givenConfigured();
        SearchScope noProject = new SearchScope(TENANT, "", null, null);
        assertThatThrownBy(() ->
                gate.setOverride(noProject, INSTANCE, ManualState.DISABLED))
                .isInstanceOf(ZarniwoopException.class);
        assertThatThrownBy(() ->
                gate.setOverride(SCOPE, "", ManualState.DISABLED))
                .isInstanceOf(ZarniwoopException.class);
    }
}
