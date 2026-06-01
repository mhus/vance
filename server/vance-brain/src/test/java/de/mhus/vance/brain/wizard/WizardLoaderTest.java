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
                TENANT, HomeBootstrapService.TENANT_PROJECT_NAME, "_vance/wizards/gremium.yaml"))
                .thenReturn(Optional.of(new LookupResult(
                        "_vance/wizards/gremium.yaml", GREMIUM_YAML,
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
        when(documentService.findByPath(TENANT, PROJECT, "_vance/wizards/gremium.yaml"))
                .thenReturn(Optional.of(projectDoc));
        when(documentService.readContent(projectDoc)).thenReturn(GREMIUM_YAML);

        Optional<ResolvedWizard> hit = loader.load(TENANT, PROJECT, USER, "gremium");

        assertThat(hit).isPresent();
        assertThat(hit.get().source()).isEqualTo(WizardSource.PROJECT);
    }

    @Test
    void user_layer_wins_when_project_misses() {
        when(documentService.findByPath(TENANT, PROJECT, "_vance/wizards/gremium.yaml"))
                .thenReturn(Optional.empty());

        DocumentDocument userDoc = projectDoc(GREMIUM_YAML);
        when(documentService.findByPath(TENANT, "_user_" + USER, "_vance/wizards/gremium.yaml"))
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
                new LookupResult("_vance/wizards/broken.yaml", yaml, LookupResult.Source.VANCE, null)));

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
                new LookupResult("_vance/wizards/broken.yaml", yaml, LookupResult.Source.VANCE, null)));

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
                new LookupResult("_vance/wizards/host.yaml", yaml, LookupResult.Source.VANCE, null)));

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
                new LookupResult("_vance/wizards/broken.yaml", yaml, LookupResult.Source.VANCE, null)));

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
                new LookupResult("_vance/wizards/broken.yaml", yaml, LookupResult.Source.VANCE, null)));

        assertThatThrownBy(() -> loader.load(TENANT, null, null, "broken"))
                .isInstanceOf(WizardParseException.class)
                .hasMessageContaining("Pebble");
    }

    @Test
    void availableIn_defaultsTo_visibleEverywhere() {
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());
        when(documentService.lookupCascade(any(), any(), any())).thenReturn(Optional.of(
                new LookupResult("_vance/wizards/gremium.yaml", GREMIUM_YAML,
                        LookupResult.Source.VANCE, null)));

        ResolvedWizard w = loader.load(TENANT, null, null, "gremium").orElseThrow();

        assertThat(w.availableIn()).containsExactly("*");
    }

    @Test
    void parses_availableIn_list() {
        String yaml = """
                title:       { en: "Test" }
                description: { en: "Test" }
                availableIn: [ "_user_*", "_tenant" ]
                fields:
                  - name: x
                    type: string
                    label: { en: "X" }
                promptTemplate: "."
                """;
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());
        when(documentService.lookupCascade(any(), any(), any())).thenReturn(Optional.of(
                new LookupResult("_vance/wizards/eddie-only.yaml", yaml,
                        LookupResult.Source.RESOURCE, null)));

        ResolvedWizard w = loader.load(TENANT, null, null, "eddie-only").orElseThrow();

        assertThat(w.availableIn()).containsExactly("_user_*", "_tenant");
    }

    @Test
    void rejects_availableIn_with_blank_entry() {
        String yaml = """
                title:       { en: "Test" }
                description: { en: "Test" }
                availableIn: [ "_user_*", "" ]
                fields:
                  - name: x
                    type: string
                    label: { en: "X" }
                promptTemplate: "."
                """;
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());
        when(documentService.lookupCascade(any(), any(), any())).thenReturn(Optional.of(
                new LookupResult("_vance/wizards/broken.yaml", yaml,
                        LookupResult.Source.VANCE, null)));

        assertThatThrownBy(() -> loader.load(TENANT, null, null, "broken"))
                .isInstanceOf(WizardParseException.class)
                .hasMessageContaining("availableIn[1]");
    }

    @Test
    void rejects_availableIn_when_not_a_list() {
        String yaml = """
                title:       { en: "Test" }
                description: { en: "Test" }
                availableIn: "_user_*"
                fields:
                  - name: x
                    type: string
                    label: { en: "X" }
                promptTemplate: "."
                """;
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());
        when(documentService.lookupCascade(any(), any(), any())).thenReturn(Optional.of(
                new LookupResult("_vance/wizards/broken.yaml", yaml,
                        LookupResult.Source.VANCE, null)));

        assertThatThrownBy(() -> loader.load(TENANT, null, null, "broken"))
                .isInstanceOf(WizardParseException.class)
                .hasMessageContaining("availableIn");
    }

    @Test
    void isAvailableIn_matches_glob_patterns() {
        assertThat(WizardLoader.isAvailableIn(java.util.List.of("*"), "_tenant")).isTrue();
        assertThat(WizardLoader.isAvailableIn(java.util.List.of("*"), "research")).isTrue();

        // Pure include match.
        assertThat(WizardLoader.isAvailableIn(
                java.util.List.of("_user_*"), "_user_alice")).isTrue();
        assertThat(WizardLoader.isAvailableIn(
                java.util.List.of("_user_*"), "_tenant")).isFalse();
        assertThat(WizardLoader.isAvailableIn(
                java.util.List.of("_user_*", "_tenant"), "_tenant")).isTrue();

        // Exact name.
        assertThat(WizardLoader.isAvailableIn(
                java.util.List.of("research-2026"), "research-2026")).isTrue();
        assertThat(WizardLoader.isAvailableIn(
                java.util.List.of("research-2026"), "research")).isFalse();

        // Exclude-only list implies "*" as include.
        assertThat(WizardLoader.isAvailableIn(
                java.util.List.of("!_*"), "research")).isTrue();
        assertThat(WizardLoader.isAvailableIn(
                java.util.List.of("!_*"), "_user_alice")).isFalse();
        assertThat(WizardLoader.isAvailableIn(
                java.util.List.of("!_*"), "_tenant")).isFalse();

        // Mixed include + exclude: exclude trumps a positive match.
        assertThat(WizardLoader.isAvailableIn(
                java.util.List.of("*", "!_user_*"), "_user_alice")).isFalse();
        assertThat(WizardLoader.isAvailableIn(
                java.util.List.of("*", "!_user_*"), "research")).isTrue();

        // Empty patterns degenerate to visible.
        assertThat(WizardLoader.isAvailableIn(java.util.List.of(), "anywhere")).isTrue();
    }

    @Test
    void listAll_filters_outBundledWizardsThatDoNotMatchProjectGlob() {
        String eddieOnlyYaml = """
                title:       { en: "Create project" }
                description: { en: "Setup wizard" }
                availableIn: [ "_user_*", "_tenant" ]
                fields:
                  - name: name
                    type: string
                    label: { en: "Name" }
                promptTemplate: "create '{{ name }}'."
                """;
        String everywhereYaml = """
                title:       { en: "Council" }
                description: { en: "Reusable group" }
                fields:
                  - name: title
                    type: string
                    label: { en: "Title" }
                promptTemplate: "build '{{ title }}'."
                """;

        // The tenant tier returns both, the user and project tiers are empty.
        when(documentService.listByPrefixCascade(
                eq(TENANT), eq(HomeBootstrapService.TENANT_PROJECT_NAME), eq("_vance/wizards/")))
                .thenReturn(Map.of(
                        "_vance/wizards/create-project.yaml", new LookupResult(
                                "_vance/wizards/create-project.yaml", eddieOnlyYaml,
                                LookupResult.Source.RESOURCE, null),
                        "_vance/wizards/gremium.yaml", new LookupResult(
                                "_vance/wizards/gremium.yaml", everywhereYaml,
                                LookupResult.Source.RESOURCE, null)));
        when(documentService.listByPrefixCascade(
                eq(TENANT), eq("_user_" + USER), eq("_vance/wizards/")))
                .thenReturn(Map.of());
        when(documentService.listByPrefixCascade(
                eq(TENANT), eq(PROJECT), eq("_vance/wizards/")))
                .thenReturn(Map.of());

        // In a real Arthur project: create-project is filtered out, gremium remains.
        var inArthur = loader.listAll(TENANT, PROJECT, USER);
        assertThat(inArthur).extracting(ResolvedWizard::name)
                .containsExactly("gremium");

        // In Eddie (projectId blank → treated as _tenant): both are visible.
        var inEddie = loader.listAll(TENANT, null, USER);
        assertThat(inEddie).extracting(ResolvedWizard::name)
                .containsExactlyInAnyOrder("create-project", "gremium");
    }

    @Test
    void listAll_dedups_acrossTiers_innermostWins() {
        when(documentService.listByPrefixCascade(
                eq(TENANT), eq(HomeBootstrapService.TENANT_PROJECT_NAME), eq("_vance/wizards/")))
                .thenReturn(Map.of(
                        "_vance/wizards/gremium.yaml", new LookupResult(
                                "_vance/wizards/gremium.yaml", GREMIUM_YAML,
                                LookupResult.Source.RESOURCE, null)));
        when(documentService.listByPrefixCascade(
                eq(TENANT), eq("_user_" + USER), eq("_vance/wizards/")))
                .thenReturn(Map.of(
                        "_vance/wizards/gremium.yaml", new LookupResult(
                                "_vance/wizards/gremium.yaml", GREMIUM_YAML,
                                LookupResult.Source.PROJECT, projectDoc(GREMIUM_YAML))));
        when(documentService.listByPrefixCascade(
                eq(TENANT), eq(PROJECT), eq("_vance/wizards/")))
                .thenReturn(Map.of());

        var all = loader.listAll(TENANT, PROJECT, USER);

        assertThat(all).hasSize(1);
        assertThat(all.get(0).source()).isEqualTo(WizardSource.USER);
    }

    private static DocumentDocument projectDoc(String content) {
        DocumentDocument doc = new DocumentDocument();
        doc.setStatus(DocumentStatus.ACTIVE);
        doc.setPath("_vance/wizards/gremium.yaml");
        // Content access goes through documentService.readContent — stubbed by caller.
        return doc;
    }
}
