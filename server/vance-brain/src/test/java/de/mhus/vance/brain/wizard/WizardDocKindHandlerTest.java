package de.mhus.vance.brain.wizard;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.kind.validate.DocRefs;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Validation surface of the {@code vance-wizard} document kind. The
 * handler delegates to the canonical {@link WizardLoader} parse, so a
 * finding means exactly what {@link WizardLoader#listAll} does with the
 * same body: WARN and skip — the wizard silently disappears.
 */
@ExtendWith(MockitoExtension.class)
class WizardDocKindHandlerTest {

    @Mock
    private DocRefs docRefs;

    @Mock
    private de.mhus.vance.shared.document.DocumentService documentService;

    @Mock
    private de.mhus.vance.brain.prompt.PromptTemplateRenderer templateRenderer;

    private WizardDocKindHandler handler;

    @org.junit.jupiter.api.BeforeEach
    void buildHandler() {
        handler = new WizardDocKindHandler(new WizardLoader(documentService, templateRenderer));
    }

    private KindValidationContext ctx(String docPath) {
        return new KindValidationContext("t", "p", docPath, "application/yaml", docRefs);
    }

    @Test
    void detectsPath_claimsTheWizardTree() {
        assertThat(handler.detectsPath("_vance/wizards/essay.yaml")).isTrue();
        assertThat(handler.detectsPath("_vance/wizards-old/x.yaml")).isFalse();
        assertThat(handler.detectsPath("_vance/workflows/x.yaml")).isFalse();
    }

    @Test
    void getName_isVanceWizard() {
        assertThat(handler.getName()).isEqualTo("vance-wizard");
    }

    @Test
    void validate_minimalWizard_hasNoFindings() {
        String yaml = """
                title:
                  de: "Aufsatz"
                  en: "Essay"
                description: One field, one prompt.
                fields:
                  - name: subject
                    type: string
                    label:
                      de: "Thema"
                      en: "Subject"
                promptTemplate: |
                  Write an essay about {{ subject }}.
                """;

        assertThat(handler.validate(yaml, ctx("_vance/wizards/my-wizard.yaml"))).isEmpty();
    }

    @Test
    void validate_stringTitleShortcut_isValid() {
        // A plain string localizes to 'en' — the shortcut the loader accepts.
        String yaml = """
                title: My wizard
                description: One field, one prompt.
                fields:
                  - name: subject
                    type: string
                    label: Subject
                promptTemplate: |
                  Write about {{ subject }}.
                """;

        assertThat(handler.validate(yaml, ctx("_vance/wizards/short.yaml"))).isEmpty();
    }

    @Test
    void validate_missingPromptTemplate_isAnError() {
        var findings = handler.validate(
                "title: t\ndescription: d\nfields:\n  - name: x\n    type: string\n    label: x\n",
                ctx("_vance/wizards/no-prompt.yaml"));

        assertThat(findings).filteredOn(f -> f.code().equals("wizard-parse")).allSatisfy(f -> {
            assertThat(f.level()).isEqualTo(Finding.Level.ERROR);
            assertThat(f.message()).contains("promptTemplate");
        });
    }

    @Test
    void validate_withoutFields_isAnError() {
        var findings = handler.validate(
                "title: t\ndescription: d\npromptTemplate: |\n  Go.\n", ctx("_vance/wizards/no-fields.yaml"));

        assertThat(findings)
                .filteredOn(f -> f.code().equals("wizard-parse"))
                .allSatisfy(f -> assertThat(f.message()).contains("at least one field"));
    }

    @Test
    void validate_brokenPebbleTemplate_isAnError() {
        // A Pebble syntax error in promptTemplate fails compileTemplate —
        // the loader would skip the wizard over it.
        var findings = handler.validate(
                "title: t\ndescription: d\n"
                        + "fields:\n  - name: x\n    type: string\n    label: x\n"
                        + "promptTemplate: |\n  {% if unclosed\n",
                ctx("_vance/wizards/bad-pebble.yaml"));

        assertThat(findings)
                .filteredOn(f -> f.code().equals("wizard-parse"))
                .allSatisfy(f -> assertThat(f.level()).isEqualTo(Finding.Level.ERROR));
    }

    @Test
    void validate_brokenYaml_isAnError() {
        var findings = handler.validate("title: [\n", ctx("_vance/wizards/broken.yaml"));

        assertThat(findings)
                .filteredOn(f -> f.code().equals("wizard-parse"))
                .allSatisfy(f -> assertThat(f.level()).isEqualTo(Finding.Level.ERROR));
    }

    @Test
    void validate_nonMapBody_isAnError() {
        var findings = handler.validate("- just\n- a\n- list\n", ctx("_vance/wizards/list.yaml"));

        assertThat(findings)
                .filteredOn(f -> f.code().equals("wizard-parse"))
                .allSatisfy(f -> assertThat(f.message()).contains("top-level map"));
    }
}
