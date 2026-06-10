package de.mhus.vance.brain.fenchurch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.fenchurch.FenchurchStyleService.Layer;
import de.mhus.vance.brain.fenchurch.FenchurchStyleService.Scope;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FenchurchStyleServiceTest {

    private static final String TENANT = "acme";
    private static final String USER = "alice";
    private static final String PROJECT = "demo";
    private static final String PROCESS = "proc-1";
    private static final String KEY = FenchurchStyleService.SETTING_KEY;

    private SettingService settings;
    private FenchurchStyleService service;

    @BeforeEach
    void setUp() {
        settings = mock(SettingService.class);
        service = new FenchurchStyleService(settings);
    }

    // ──────────────────── composeMergedPrompt ────────────────────

    @Test
    void merged_prompt_joins_layers_with_comma() {
        List<Layer> layers = List.of(
                new Layer(Scope.TENANT, "clean"),
                new Layer(Scope.USER, "watercolor"),
                new Layer(Scope.PROJECT, "medieval"),
                new Layer(Scope.SESSION, "transparent background"));

        assertThat(service.composeMergedPrompt(layers))
                .isEqualTo("clean, watercolor, medieval, transparent background");
    }

    @Test
    void merged_prompt_skips_blank_layers() {
        List<Layer> layers = List.of(
                new Layer(Scope.TENANT, "clean"),
                new Layer(Scope.USER, "   "),
                new Layer(Scope.PROJECT, "medieval"));

        assertThat(service.composeMergedPrompt(layers))
                .isEqualTo("clean, medieval");
    }

    @Test
    void merged_prompt_trims_each_layer() {
        List<Layer> layers = List.of(
                new Layer(Scope.TENANT, "  clean  "),
                new Layer(Scope.USER, "watercolor "));

        assertThat(service.composeMergedPrompt(layers))
                .isEqualTo("clean, watercolor");
    }

    @Test
    void merged_prompt_returns_empty_for_no_layers() {
        assertThat(service.composeMergedPrompt(List.of())).isEmpty();
    }

    // ──────────────────── applyNoneCutoff ────────────────────

    @Test
    void none_marker_in_project_suppresses_outer_layers() {
        List<Layer> layers = List.of(
                new Layer(Scope.TENANT, "clean"),
                new Layer(Scope.USER, "watercolor"),
                new Layer(Scope.PROJECT, FenchurchStyleService.NONE_MARKER),
                new Layer(Scope.SESSION, "for-prod"));

        assertThat(service.composeMergedPrompt(layers)).isEqualTo("for-prod");
    }

    @Test
    void none_marker_in_session_clears_everything() {
        List<Layer> layers = List.of(
                new Layer(Scope.TENANT, "clean"),
                new Layer(Scope.USER, "watercolor"),
                new Layer(Scope.SESSION, FenchurchStyleService.NONE_MARKER));

        assertThat(service.composeMergedPrompt(layers)).isEmpty();
    }

    @Test
    void none_marker_in_tenant_acts_as_default_clean_slate() {
        List<Layer> layers = List.of(
                new Layer(Scope.TENANT, FenchurchStyleService.NONE_MARKER),
                new Layer(Scope.PROJECT, "medieval"));

        assertThat(service.composeMergedPrompt(layers)).isEqualTo("medieval");
    }

    @Test
    void innermost_none_marker_wins_when_multiple_present() {
        List<Layer> layers = List.of(
                new Layer(Scope.TENANT, FenchurchStyleService.NONE_MARKER),
                new Layer(Scope.USER, "watercolor"),
                new Layer(Scope.PROJECT, FenchurchStyleService.NONE_MARKER),
                new Layer(Scope.SESSION, "transparent"));

        // Project's __none__ is innermost — it drops user (and the
        // outer tenant marker), keeping only session.
        assertThat(service.composeMergedPrompt(layers)).isEqualTo("transparent");
    }

    // ──────────────────── readLayers ────────────────────

    @Test
    void read_layers_returns_only_populated_scopes() {
        stub(SettingService.SCOPE_PROJECT,
                HomeBootstrapService.TENANT_PROJECT_NAME, "tenant-style");
        stub(SettingService.SCOPE_PROJECT,
                HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + USER, null);
        stub(SettingService.SCOPE_PROJECT, PROJECT, "project-style");
        stub(SettingService.SCOPE_THINK_PROCESS, PROCESS, null);

        List<Layer> layers = service.readLayers(TENANT, USER, PROJECT, PROCESS);

        assertThat(layers).containsExactly(
                new Layer(Scope.TENANT, "tenant-style"),
                new Layer(Scope.PROJECT, "project-style"));
    }

    @Test
    void read_layers_skips_user_when_userId_null() {
        stub(SettingService.SCOPE_PROJECT,
                HomeBootstrapService.TENANT_PROJECT_NAME, "tenant-style");
        stub(SettingService.SCOPE_PROJECT, PROJECT, "project-style");

        List<Layer> layers = service.readLayers(TENANT, null, PROJECT, null);

        assertThat(layers).hasSize(2);
        assertThat(layers).extracting(Layer::scope)
                .containsExactly(Scope.TENANT, Scope.PROJECT);
    }

    @Test
    void read_layers_collapses_project_when_equal_to_tenant_marker() {
        // projectId == "_tenant" must not double-read the tenant layer.
        stub(SettingService.SCOPE_PROJECT,
                HomeBootstrapService.TENANT_PROJECT_NAME, "only-once");

        List<Layer> layers = service.readLayers(
                TENANT, null, HomeBootstrapService.TENANT_PROJECT_NAME, null);

        assertThat(layers).containsExactly(new Layer(Scope.TENANT, "only-once"));
    }

    // ──────────────────── writeScope ────────────────────

    @Test
    void write_scope_session_routes_to_think_process_setting() {
        service.writeScope(TENANT, Scope.SESSION, "transparent",
                USER, PROJECT, PROCESS);

        verify(settings).setStringValue(eq(TENANT),
                eq(SettingService.SCOPE_THINK_PROCESS),
                eq(PROCESS), eq(KEY), eq("transparent"));
    }

    @Test
    void write_scope_user_routes_to_hub_project() {
        service.writeScope(TENANT, Scope.USER, "watercolor",
                USER, PROJECT, PROCESS);

        verify(settings).setStringValue(eq(TENANT),
                eq(SettingService.SCOPE_PROJECT),
                eq(HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + USER),
                eq(KEY), eq("watercolor"));
    }

    @Test
    void write_scope_project_routes_to_project_setting() {
        service.writeScope(TENANT, Scope.PROJECT, "medieval",
                USER, PROJECT, PROCESS);

        verify(settings).setStringValue(eq(TENANT),
                eq(SettingService.SCOPE_PROJECT),
                eq(PROJECT), eq(KEY), eq("medieval"));
    }

    @Test
    void write_scope_tenant_routes_to_tenant_marker_project() {
        service.writeScope(TENANT, Scope.TENANT, "clean",
                USER, PROJECT, PROCESS);

        verify(settings).setStringValue(eq(TENANT),
                eq(SettingService.SCOPE_PROJECT),
                eq(HomeBootstrapService.TENANT_PROJECT_NAME),
                eq(KEY), eq("clean"));
    }

    @Test
    void write_scope_rejects_blank_prefix() {
        assertThatThrownBy(() -> service.writeScope(
                TENANT, Scope.SESSION, "  ", USER, PROJECT, PROCESS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void write_scope_rejects_too_long_prefix() {
        String tooLong = "x".repeat(501);
        assertThatThrownBy(() -> service.writeScope(
                TENANT, Scope.SESSION, tooLong, USER, PROJECT, PROCESS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500");
    }

    @Test
    void write_scope_rejects_user_without_userId() {
        assertThatThrownBy(() -> service.writeScope(
                TENANT, Scope.USER, "watercolor", null, PROJECT, PROCESS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    void write_scope_rejects_session_without_processId() {
        assertThatThrownBy(() -> service.writeScope(
                TENANT, Scope.SESSION, "transparent", USER, PROJECT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processId");
    }

    @Test
    void write_scope_accepts_none_marker_as_valid_prefix() {
        // The sentinel passes validation — its semantic is interpreted
        // by composeMergedPrompt later.
        service.writeScope(TENANT, Scope.PROJECT,
                FenchurchStyleService.NONE_MARKER,
                USER, PROJECT, PROCESS);

        verify(settings).setStringValue(eq(TENANT),
                eq(SettingService.SCOPE_PROJECT),
                eq(PROJECT), eq(KEY),
                eq(FenchurchStyleService.NONE_MARKER));
    }

    // ──────────────────── Helpers ────────────────────

    private void stub(String referenceType, String referenceId, String value) {
        when(settings.getStringValue(
                eq(TENANT), eq(referenceType), eq(referenceId), eq(KEY)))
                .thenReturn(value);
    }
}
