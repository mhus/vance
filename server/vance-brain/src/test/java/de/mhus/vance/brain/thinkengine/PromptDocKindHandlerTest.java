package de.mhus.vance.brain.thinkengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import de.mhus.vance.brain.prompt.PromptTemplateException;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.document.kind.validate.DocRefs;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Validation surface of the {@code vance-prompt} document kind. Unlike the
 * theme kinds there is a hard failure mode to catch: the turn renders the
 * document with Pebble, so a template syntax error fails every spawn of the
 * tenant — the finding is the difference between an edit-time message and
 * that. A blank override is a hint: the resolver filters blank content and
 * the bundled prompt keeps serving.
 */
class PromptDocKindHandlerTest {

    private static final DocRefs NO_REFS = new DocRefs() {
        @Override
        public boolean exists(String path) {
            return false;
        }

        @Override
        public @Nullable String kindOf(String path) {
            return null;
        }

        @Override
        public @Nullable Map<String, Object> readYaml(String path) {
            return null;
        }
    };

    private final PromptTemplateRenderer renderer = mock(PromptTemplateRenderer.class);
    private final PromptDocKindHandler handler = new PromptDocKindHandler(renderer);

    private static KindValidationContext ctx(String docPath) {
        return new KindValidationContext("t", "p", docPath, "text/markdown", NO_REFS);
    }

    @Test
    void detectsPath_claimsThePromptTree() {
        assertThat(handler.detectsPath("_vance/prompts/arthur-prompt.md")).isTrue();
        assertThat(handler.detectsPath("_vance/prompts/arthur-prompt-planning.md"))
                .isTrue();
    }

    @Test
    void detectsPath_neverClaimsOutsideTheTree() {
        assertThat(handler.detectsPath("_vance/prompts-old/x.md")).isFalse();
        assertThat(handler.detectsPath("_vance/manuals/some-manual.md")).isFalse();
        assertThat(handler.detectsPath("notes/prompts.md")).isFalse();
        assertThat(handler.detectsPath("_vance/prompts")).isFalse();
    }

    @Test
    void getName_isVancePrompt() {
        assertThat(handler.getName()).isEqualTo("vance-prompt");
    }

    @Test
    void validate_compilingBody_hasNoFindings() {
        assertThat(handler.validate("You are a worker.\n", ctx("_vance/prompts/arthur-prompt.md")))
                .isEmpty();
    }

    @Test
    void validate_templateSyntaxError_isAnError() {
        doThrow(new PromptTemplateException("unbalanced {% if %}"))
                .when(renderer)
                .compile(anyString());

        var findings = handler.validate("{% if %}", ctx("broken.md"));

        assertThat(findings).filteredOn(f -> f.code().equals("prompt-template")).allSatisfy(f -> {
            assertThat(f.level()).isEqualTo(Finding.Level.ERROR);
            assertThat(f.message()).contains("does not compile");
        });
    }

    @Test
    void validate_blankOverride_isAHintNotAnError() {
        var findings = handler.validate("   \n", ctx("blank.md"));

        assertThat(findings)
                .filteredOn(f -> f.code().equals("prompt-blank"))
                .allSatisfy(f -> assertThat(f.level()).isEqualTo(Finding.Level.WARNING));
    }
}
