package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Coverage for the v2 template DSL extensions: multi-select inputs,
 * derived-union variables, and the documents filter-overlay. Parsing
 * and pure-logic behaviour only — end-to-end apply with mongo lives
 * in {@code qa/ai-test}.
 */
class TemplateMultiSelectTest {

    @Test
    void parses_multiselect_with_per_choice_defaults() {
        TemplateDescriptor t = KitYamlMapper.parseTemplate("""
                name: atlassian
                inputs:
                  - name: features
                    type: multiselect
                    label: Atlassian Products
                    choices:
                      - { value: jira, label: Jira, default: true }
                      - { value: confluence, label: Confluence }
                """);
        TemplateInput in = t.inputs().get(0);
        assertThat(in.type()).isEqualTo(TemplateInputType.MULTI_SELECT);
        assertThat(in.choices()).hasSize(2);
        TemplateChoice jira = in.choices().get(0);
        assertThat(jira.value()).isEqualTo("jira");
        assertThat(jira.label()).isEqualTo("Jira");
        assertThat(jira.defaultSelected()).isTrue();
        TemplateChoice confluence = in.choices().get(1);
        assertThat(confluence.defaultSelected()).isFalse();
    }

    @Test
    void multiselect_with_target_setting_is_rejected() {
        // Multi-select values are intended for inline substitution and
        // feature-driven document filtering; storing them as encrypted
        // settings would be a category error.
        assertThatThrownBy(() -> KitYamlMapper.parseTemplate("""
                name: bad
                inputs:
                  - name: features
                    type: multiselect
                    choices: [a, b]
                    target: { kind: setting, scope: project, key: x.features }
                """))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("multi_select");
    }

    @Test
    void empty_choices_on_multiselect_rejected() {
        assertThatThrownBy(() -> KitYamlMapper.parseTemplate("""
                name: bad
                inputs:
                  - name: features
                    type: multiselect
                """))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("choices");
    }

    @Test
    void parses_derived_union_block() {
        TemplateDescriptor t = KitYamlMapper.parseTemplate("""
                name: atlassian
                inputs:
                  - name: features
                    type: multiselect
                    choices:
                      - { value: jira }
                      - { value: confluence }
                derived:
                  - name: oauth_scopes
                    kind: union
                    from: features
                    base: [read:me, offline_access]
                    perChoice:
                      jira: [read:jira-work, write:jira-work]
                      confluence: [read:confluence-content.all]
                """);
        assertThat(t.derived()).hasSize(1);
        TemplateDerived d = t.derived().get(0);
        assertThat(d.name()).isEqualTo("oauth_scopes");
        assertThat(d.kind()).isEqualTo(TemplateDerived.Kind.UNION);
        assertThat(d.from()).isEqualTo("features");
        assertThat(d.base()).containsExactly("read:me", "offline_access");
        assertThat(d.perChoice()).containsKeys("jira", "confluence");
    }

    @Test
    void derived_from_unknown_input_is_rejected() {
        assertThatThrownBy(() -> KitYamlMapper.parseTemplate("""
                name: bad
                inputs:
                  - name: features
                    type: multiselect
                    choices: [a, b]
                derived:
                  - name: x
                    kind: union
                    from: nonexistent
                """))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("multi-select");
    }

    @Test
    void derived_perChoice_with_unknown_choice_is_rejected() {
        assertThatThrownBy(() -> KitYamlMapper.parseTemplate("""
                name: bad
                inputs:
                  - name: features
                    type: multiselect
                    choices: [a, b]
                derived:
                  - name: x
                    kind: union
                    from: features
                    perChoice:
                      c: [whatever]
                """))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("'c'");
    }

    @Test
    void derived_name_collision_with_input_is_rejected() {
        assertThatThrownBy(() -> KitYamlMapper.parseTemplate("""
                name: bad
                inputs:
                  - name: features
                    type: multiselect
                    choices: [a]
                  - name: oauth_scopes
                    type: string
                derived:
                  - name: oauth_scopes
                    kind: union
                    from: features
                """))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("shadows");
    }

    @Test
    void parses_documents_overlay_with_requires_string_or_list() {
        TemplateDescriptor t = KitYamlMapper.parseTemplate("""
                name: atlassian
                inputs:
                  - name: features
                    type: multiselect
                    choices: [jira, confluence]
                documents:
                  - { path: _vance/server-tools/jira_rest.yaml, requires: jira }
                  - { path: _vance/server-tools/atlassian_admin.yaml, requires: [jira, confluence] }
                """);
        assertThat(t.documents()).hasSize(2);
        assertThat(t.documents().get(0).requires()).containsExactly("jira");
        assertThat(t.documents().get(1).requires()).containsExactly("jira", "confluence");
    }

    @Test
    void documents_overlay_with_unknown_feature_is_rejected() {
        assertThatThrownBy(() -> KitYamlMapper.parseTemplate("""
                name: bad
                inputs:
                  - name: features
                    type: multiselect
                    choices: [a, b]
                documents:
                  - { path: x.yaml, requires: c }
                """))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("'c'");
    }

    @Test
    void documents_overlay_with_traversal_is_rejected() {
        assertThatThrownBy(() -> KitYamlMapper.parseTemplate("""
                name: bad
                inputs:
                  - name: features
                    type: multiselect
                    choices: [a]
                documents:
                  - { path: ../escape.yaml, requires: a }
                """))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("relative path");
    }

    // ──────────────────── derived evaluator unit ────────────────────

    @Test
    void union_deduplicates_and_preserves_choice_order() {
        TemplateInput featuresInput = new TemplateInput(
                "features",
                TemplateInputType.MULTI_SELECT,
                "Features",
                null,
                true,
                null,
                List.of(
                        new TemplateChoice("jira", null, false),
                        new TemplateChoice("confluence", null, false)),
                TemplateInputTarget.documentInline());
        TemplateDerived derived = new TemplateDerived(
                "oauth_scopes",
                TemplateDerived.Kind.UNION,
                "features",
                List.of("read:me", "offline_access"),
                Map.of(
                        "jira", List.of("read:jira-work", "write:jira-work", "read:me"),
                        "confluence", List.of("read:confluence-content.all")));
        Map<String, TemplateInput> byName = new LinkedHashMap<>();
        byName.put("features", featuresInput);

        // User selected confluence then jira — but the evaluator iterates in
        // the input's declared choice order, so the resulting list is stable
        // regardless of click order.
        Map<String, String> sanitised = Map.of(
                "features", TemplateApplier.renderJsonStringArray(List.of("confluence", "jira")));

        Map<String, List<String>> out = TemplateApplier.evaluateDerived(
                List.of(derived), sanitised, byName);

        assertThat(out.get("oauth_scopes")).containsExactly(
                "read:me",                          // from base
                "offline_access",                   // from base
                "read:jira-work",                   // jira (declared first)
                "write:jira-work",
                                                    // jira's "read:me" deduped
                "read:confluence-content.all");     // confluence
    }
}
