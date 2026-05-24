package de.mhus.vance.brain.wizard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.DocumentStatus;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WizardLoaderTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final String USER = "alice";

    private static final String GREMIUM_YAML = """
            title:       { de: "Gremium anlegen", en: "Create a council" }
            description: { de: "Beraterkreis",     en: "Advisory group" }
            icon: users
            category: strategie
            fields:
              - name: outputName
                type: string
                required: true
                label: { de: "Name", en: "Name" }
              - name: members
                type: repeat
                min: 2
                label: { de: "Mitglieder", en: "Members" }
                item:
                  - name: name
                    type: string
                    required: true
                    label: { de: "Name", en: "Name" }
                  - name: description
                    type: textarea
                    required: true
                    label: { de: "Beschreibung", en: "Description" }
            promptTemplate: |
              Build a council named '{{ outputName }}' with members:
              {% for m in members %}- {{ m.name }}: {{ m.description }}
              {% endfor %}
            """;

    private final DocumentService documentService = mock(DocumentService.class);
    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();
    private final WizardLoader loader = new WizardLoader(documentService, renderer);

    @Test
    void parses_full_wizard_yaml_through_vance_layer() {
        // 1. Project lookup misses (findByPath returns empty).
        when(documentService.findByPath(eq(TENANT), eq(PROJECT), any())).thenReturn(Optional.empty());
        when(documentService.findByPath(eq(TENANT), eq("_user_" + USER), any())).thenReturn(Optional.empty());
        // 2. Cascade returns the wizard from VANCE.
        when(documentService.lookupCascade(
                TENANT, HomeBootstrapService.TENANT_PROJECT_NAME, "wizards/gremium.yaml"))
                .thenReturn(Optional.of(new LookupResult(
                        "wizards/gremium.yaml", GREMIUM_YAML,
                        LookupResult.Source.VANCE, null)));

        Optional<ResolvedWizard> hit = loader.load(TENANT, PROJECT, USER, "gremium");

        assertThat(hit).isPresent();
        ResolvedWizard w = hit.get();
        assertThat(w.name()).isEqualTo("gremium");
        assertThat(w.source()).isEqualTo(WizardSource.VANCE);
        assertThat(w.title()).containsEntry("de", "Gremium anlegen");
        assertThat(w.fields()).hasSize(2);
        assertThat(w.fields().get(1).getType()).isEqualTo("repeat");
        assertThat(w.fields().get(1).getItem()).hasSize(2);
        assertThat(w.promptTemplate()).contains("{% for m in members %}");
    }

    @Test
    void project_layer_overrides_vance_and_user() {
        DocumentDocument projectDoc = projectDoc(GREMIUM_YAML);
        when(documentService.findByPath(TENANT, PROJECT, "wizards/gremium.yaml"))
                .thenReturn(Optional.of(projectDoc));
        when(documentService.readContent(projectDoc)).thenReturn(GREMIUM_YAML);

        Optional<ResolvedWizard> hit = loader.load(TENANT, PROJECT, USER, "gremium");

        assertThat(hit).isPresent();
        assertThat(hit.get().source()).isEqualTo(WizardSource.PROJECT);
    }

    @Test
    void user_layer_wins_when_project_misses() {
        when(documentService.findByPath(TENANT, PROJECT, "wizards/gremium.yaml"))
                .thenReturn(Optional.empty());

        DocumentDocument userDoc = projectDoc(GREMIUM_YAML);
        when(documentService.findByPath(TENANT, "_user_" + USER, "wizards/gremium.yaml"))
                .thenReturn(Optional.of(userDoc));
        when(documentService.readContent(userDoc)).thenReturn(GREMIUM_YAML);

        Optional<ResolvedWizard> hit = loader.load(TENANT, PROJECT, USER, "gremium");

        assertThat(hit).isPresent();
        assertThat(hit.get().source()).isEqualTo(WizardSource.USER);
    }

    @Test
    void missing_promptTemplate_isRejected() {
        String yaml = """
                title:       { en: "Test" }
                description: { en: "Test" }
                fields:
                  - name: x
                    type: string
                    label: { en: "X" }
                """;
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());
        when(documentService.lookupCascade(any(), any(), any())).thenReturn(Optional.of(
                new LookupResult("wizards/broken.yaml", yaml, LookupResult.Source.VANCE, null)));

        assertThatThrownBy(() -> loader.load(TENANT, null, null, "broken"))
                .isInstanceOf(WizardParseException.class)
                .hasMessageContaining("promptTemplate");
    }

    @Test
    void invalid_pebble_template_isRejected() {
        String yaml = """
                title:       { en: "Test" }
                description: { en: "Test" }
                fields:
                  - name: x
                    type: string
                    label: { en: "X" }
                promptTemplate: "{% if unclosed"
                """;
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());
        when(documentService.lookupCascade(any(), any(), any())).thenReturn(Optional.of(
                new LookupResult("wizards/broken.yaml", yaml, LookupResult.Source.VANCE, null)));

        assertThatThrownBy(() -> loader.load(TENANT, null, null, "broken"))
                .isInstanceOf(WizardParseException.class)
                .hasMessageContaining("Pebble");
    }

    @Test
    void empty_lookup_returnsEmpty() {
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());
        when(documentService.lookupCascade(any(), any(), any())).thenReturn(Optional.empty());

        assertThat(loader.load(TENANT, PROJECT, USER, "missing")).isEmpty();
    }

    @Test
    void blank_name_returnsEmpty() {
        assertThat(loader.load(TENANT, PROJECT, USER, "")).isEmpty();
    }

    @Test
    void parses_suggestedFollowUps_with_condition_and_prefill() {
        String yaml = """
                title:       { en: "Test" }
                description: { en: "Test" }
                fields:
                  - name: outputName
                    type: string
                    required: true
                    label: { en: "Name" }
                  - name: runNext
                    type: boolean
                    label: { en: "Continue?" }
                promptTemplate: |
                  Build '{{ outputName }}'.
                suggestedFollowUps:
                  - wizard: next-step
                    label: { de: "Weiter", en: "Continue" }
                    prefill:
                      ref: "{{ outputName }}"
                    condition: runNext == "true"
                """;
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());
        when(documentService.lookupCascade(any(), any(), any())).thenReturn(Optional.of(
                new LookupResult("wizards/host.yaml", yaml, LookupResult.Source.VANCE, null)));

        ResolvedWizard w = loader.load(TENANT, null, null, "host").orElseThrow();

        assertThat(w.followUps()).hasSize(1);
        WizardFollowUp fu = w.followUps().get(0);
        assertThat(fu.wizard()).isEqualTo("next-step");
        assertThat(fu.label()).containsEntry("de", "Weiter");
        assertThat(fu.prefill()).containsEntry("ref", "{{ outputName }}");
        assertThat(fu.condition()).isEqualTo("runNext == \"true\"");
    }

    @Test
    void rejects_followUp_without_target_wizard() {
        String yaml = """
                title:       { en: "Test" }
                description: { en: "Test" }
                fields:
                  - name: x
                    type: string
                    label: { en: "X" }
                promptTemplate: "."
                suggestedFollowUps:
                  - label: { en: "Continue" }
                """;
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());
        when(documentService.lookupCascade(any(), any(), any())).thenReturn(Optional.of(
                new LookupResult("wizards/broken.yaml", yaml, LookupResult.Source.VANCE, null)));

        assertThatThrownBy(() -> loader.load(TENANT, null, null, "broken"))
                .isInstanceOf(WizardParseException.class)
                .hasMessageContaining("wizard");
    }

    @Test
    void rejects_followUp_prefill_with_broken_pebble() {
        String yaml = """
                title:       { en: "Test" }
                description: { en: "Test" }
                fields:
                  - name: x
                    type: string
                    label: { en: "X" }
                promptTemplate: "."
                suggestedFollowUps:
                  - wizard: target
                    label: { en: "Continue" }
                    prefill:
                      ref: "{% if unclosed"
                """;
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());
        when(documentService.lookupCascade(any(), any(), any())).thenReturn(Optional.of(
                new LookupResult("wizards/broken.yaml", yaml, LookupResult.Source.VANCE, null)));

        assertThatThrownBy(() -> loader.load(TENANT, null, null, "broken"))
                .isInstanceOf(WizardParseException.class)
                .hasMessageContaining("Pebble");
    }

    @Test
    void listAll_dedups_acrossTiers_innermostWins() {
        when(documentService.listByPrefixCascade(
                eq(TENANT), eq(HomeBootstrapService.TENANT_PROJECT_NAME), eq("wizards/")))
                .thenReturn(Map.of(
                        "wizards/gremium.yaml", new LookupResult(
                                "wizards/gremium.yaml", GREMIUM_YAML,
                                LookupResult.Source.RESOURCE, null)));
        when(documentService.listByPrefixCascade(
                eq(TENANT), eq("_user_" + USER), eq("wizards/")))
                .thenReturn(Map.of(
                        "wizards/gremium.yaml", new LookupResult(
                                "wizards/gremium.yaml", GREMIUM_YAML,
                                LookupResult.Source.PROJECT, projectDoc(GREMIUM_YAML))));
        when(documentService.listByPrefixCascade(
                eq(TENANT), eq(PROJECT), eq("wizards/")))
                .thenReturn(Map.of());

        var all = loader.listAll(TENANT, PROJECT, USER);

        assertThat(all).hasSize(1);
        assertThat(all.get(0).source()).isEqualTo(WizardSource.USER);
    }

    private static DocumentDocument projectDoc(String content) {
        DocumentDocument doc = new DocumentDocument();
        doc.setStatus(DocumentStatus.ACTIVE);
        doc.setPath("wizards/gremium.yaml");
        // Content access goes through documentService.readContent — stubbed by caller.
        return doc;
    }
}
