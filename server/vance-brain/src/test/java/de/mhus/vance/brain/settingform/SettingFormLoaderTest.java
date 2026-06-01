package de.mhus.vance.brain.settingform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SettingFormLoaderTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final String USER = "alice";

    private static final String LLM_SETUP_YAML = """
            title:       { de: "LLM-Einstellungen", en: "LLM Settings" }
            description: { de: "Provider & Keys",   en: "Provider & keys" }
            icon: cpu-chip
            category: llm
            defaultScope: project
            fields:
              - name: provider
                type: select
                required: true
                label: { en: "Provider" }
                bindsTo: { key: "ai.default.provider" }
                choices:
                  - { value: "anthropic" }
                  - { value: "openai" }
              - name: anthropicKey
                type: password
                label: { en: "Anthropic key" }
                showIf:  "provider == 'anthropic'"
                writeIf: "provider == 'anthropic'"
                bindsTo: { key: "ai.providers.anthropic.api_key" }
            settings:
              - key: "tracing.llm.enabled"
                settingType: BOOLEAN
                value: "false"
            """;

    private final DocumentService documentService = mock(DocumentService.class);
    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();
    private final SettingFormLoader loader = new SettingFormLoader(documentService, renderer);

    @Test
    void parse_full_form_through_vance_layer_produces_resolved_form() {
        when(documentService.findByPath(eq(TENANT), any(), any())).thenReturn(Optional.empty());
        when(documentService.lookupCascade(
                TENANT, HomeBootstrapService.TENANT_PROJECT_NAME, "_vance/setting_forms/llm-setup.yaml"))
                .thenReturn(Optional.of(new LookupResult(
                        "_vance/setting_forms/llm-setup.yaml", LLM_SETUP_YAML,
                        LookupResult.Source.VANCE, null)));

        Optional<ResolvedSettingForm> hit = loader.load(TENANT, PROJECT, USER, "llm-setup");

        assertThat(hit).isPresent();
        ResolvedSettingForm f = hit.get();
        assertThat(f.name()).isEqualTo("llm-setup");
        assertThat(f.defaultScope()).isEqualTo("project");
        assertThat(f.source()).isEqualTo(SettingFormSource.VANCE);
        assertThat(f.fields()).hasSize(2);
        assertThat(f.fields().get(0).getBindsTo()).isNotNull();
        assertThat(f.fields().get(0).getBindsTo().getKey()).isEqualTo("ai.default.provider");
        assertThat(f.fields().get(1).getShowIf()).isEqualTo("provider == 'anthropic'");
        assertThat(f.fields().get(1).getWriteIf()).isEqualTo("provider == 'anthropic'");
        assertThat(f.computedSettings()).hasSize(1);
        assertThat(f.computedSettings().get(0).key()).isEqualTo("tracing.llm.enabled");
        assertThat(f.clearable()).isTrue();
    }

    @Test
    void invalid_pebble_in_showIf_rejects_form() {
        String broken = """
                title:       { en: "Broken" }
                description: { en: "Broken" }
                fields:
                  - name: x
                    type: string
                    label: { en: "X" }
                    showIf: "{% if %}{% endif %}"
                """;
        when(documentService.lookupCascade(eq(TENANT), any(), any()))
                .thenReturn(Optional.of(new LookupResult(
                        "_vance/setting_forms/broken.yaml", broken,
                        LookupResult.Source.VANCE, null)));

        assertThatThrownBy(() -> loader.load(TENANT, null, null, "broken"))
                .isInstanceOf(SettingFormParseException.class)
                .hasMessageContaining("showIf");
    }

    @Test
    void invalid_pebble_in_computed_value_rejects_form() {
        String broken = """
                title:       { en: "Broken" }
                description: { en: "Broken" }
                fields:
                  - name: x
                    type: string
                    label: { en: "X" }
                settings:
                  - key: "k"
                    value: "{% if unclosed %}"
                """;
        when(documentService.lookupCascade(eq(TENANT), any(), any()))
                .thenReturn(Optional.of(new LookupResult(
                        "_vance/setting_forms/broken.yaml", broken,
                        LookupResult.Source.VANCE, null)));

        assertThatThrownBy(() -> loader.load(TENANT, null, null, "broken"))
                .isInstanceOf(SettingFormParseException.class)
                .hasMessageContaining("value");
    }

    @Test
    void unknown_scope_rejects_form() {
        String yaml = """
                title:       { en: "X" }
                description: { en: "X" }
                defaultScope: galactic
                fields:
                  - name: x
                    type: string
                    label: { en: "X" }
                """;
        when(documentService.lookupCascade(eq(TENANT), any(), any()))
                .thenReturn(Optional.of(new LookupResult(
                        "_vance/setting_forms/broken.yaml", yaml,
                        LookupResult.Source.VANCE, null)));

        assertThatThrownBy(() -> loader.load(TENANT, null, null, "broken"))
                .isInstanceOf(SettingFormParseException.class)
                .hasMessageContaining("galactic");
    }

    @Test
    void unconditional_duplicate_key_is_rejected() {
        String yaml = """
                title:       { en: "X" }
                description: { en: "X" }
                fields:
                  - name: a
                    type: string
                    label: { en: "A" }
                    bindsTo: { key: "k" }
                  - name: b
                    type: string
                    label: { en: "B" }
                    bindsTo: { key: "k" }
                """;
        when(documentService.lookupCascade(eq(TENANT), any(), any()))
                .thenReturn(Optional.of(new LookupResult(
                        "_vance/setting_forms/broken.yaml", yaml,
                        LookupResult.Source.VANCE, null)));

        assertThatThrownBy(() -> loader.load(TENANT, null, null, "broken"))
                .isInstanceOf(SettingFormParseException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void conditional_duplicate_key_is_allowed() {
        // Two writeIf-gated entries pointing at the same key are the
        // canonical preset/custom pattern — must parse successfully.
        String yaml = """
                title:       { en: "X" }
                description: { en: "X" }
                fields:
                  - name: mode
                    type: select
                    label: { en: "Mode" }
                    choices: [{value: a}, {value: b}]
                  - name: customValue
                    type: integer
                    label: { en: "Custom" }
                    writeIf: "mode == 'a'"
                    bindsTo: { key: "x.value" }
                settings:
                  - key: "x.value"
                    settingType: INT
                    writeIf: "mode == 'b'"
                    value: "42"
                """;
        when(documentService.lookupCascade(eq(TENANT), any(), any()))
                .thenReturn(Optional.of(new LookupResult(
                        "_vance/setting_forms/ok.yaml", yaml,
                        LookupResult.Source.VANCE, null)));

        Optional<ResolvedSettingForm> hit = loader.load(TENANT, null, null, "ok");
        assertThat(hit).isPresent();
    }

    @Test
    void availableIn_filter_matches_glob_patterns() {
        assertThat(SettingFormLoader.isAvailableIn(List.of("*"), "_tenant")).isTrue();
        assertThat(SettingFormLoader.isAvailableIn(List.of("_user_*"), "_user_alice")).isTrue();
        assertThat(SettingFormLoader.isAvailableIn(List.of("_user_*"), "research")).isFalse();
        assertThat(SettingFormLoader.isAvailableIn(List.of("!_*"), "research")).isTrue();
        assertThat(SettingFormLoader.isAvailableIn(List.of("!_*"), "_tenant")).isFalse();
        assertThat(SettingFormLoader.isAvailableIn(List.of("_tenant"), "_tenant")).isTrue();
    }

    @Test
    void bindsTo_on_multi_select_is_rejected() {
        String yaml = """
                title:       { en: "X" }
                description: { en: "X" }
                fields:
                  - name: tags
                    type: multi_select
                    label: { en: "Tags" }
                    choices: [{value: a}, {value: b}]
                    bindsTo: { key: "k" }
                """;
        when(documentService.lookupCascade(eq(TENANT), any(), any()))
                .thenReturn(Optional.of(new LookupResult(
                        "_vance/setting_forms/broken.yaml", yaml,
                        LookupResult.Source.VANCE, null)));

        assertThatThrownBy(() -> loader.load(TENANT, null, null, "broken"))
                .isInstanceOf(SettingFormParseException.class)
                .hasMessageContaining("not allowed on field-type 'multi_select'");
    }

    @Test
    void missing_top_level_title_is_rejected() {
        String yaml = """
                description: { en: "X" }
                fields:
                  - name: x
                    type: string
                    label: { en: "X" }
                """;
        when(documentService.lookupCascade(eq(TENANT), any(), any()))
                .thenReturn(Optional.of(new LookupResult(
                        "_vance/setting_forms/broken.yaml", yaml,
                        LookupResult.Source.VANCE, null)));

        assertThatThrownBy(() -> loader.load(TENANT, null, null, "broken"))
                .isInstanceOf(SettingFormParseException.class)
                .hasMessageContaining("title");
    }

    @Test
    void empty_form_without_fields_or_settings_is_rejected() {
        String yaml = """
                title:       { en: "X" }
                description: { en: "X" }
                """;
        when(documentService.lookupCascade(eq(TENANT), any(), any()))
                .thenReturn(Optional.of(new LookupResult(
                        "_vance/setting_forms/broken.yaml", yaml,
                        LookupResult.Source.VANCE, null)));

        assertThatThrownBy(() -> loader.load(TENANT, null, null, "broken"))
                .isInstanceOf(SettingFormParseException.class)
                .hasMessageContaining("at least one field or one computed setting");
    }
}
