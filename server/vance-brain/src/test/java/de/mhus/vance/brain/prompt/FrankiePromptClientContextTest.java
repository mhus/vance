package de.mhus.vance.brain.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.thinkprocess.ActiveAppContext;
import de.mhus.vance.api.thinkprocess.BoundDocSelection;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The template half of the 2026-08-25 fix: wiring
 * {@link ClientTurnContextResolver} into Frankie only helps if
 * {@code frankie-prompt.md} actually renders the variables. The engine
 * and the template are edited independently, and a block that silently
 * never fires looks exactly like the bug it was written to fix.
 *
 * <p>Rendered against the bundled classpath resource rather than an
 * inline snippet, because the thing under test is the shipped file.
 */
class FrankiePromptClientContextTest {

    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();

    @Test
    void activeApp_namesTheFolderAsTheImpliedTarget() {
        String out = render(PromptContextBuilder.create()
                .activeApp(ActiveAppContext.builder()
                        .folder("apps/bistromath1").app("custom").build())
                .appInstructions("You are in a Bistromath app at `apps/bistromath1`.\n")
                .build());

        assertThat(out)
                .contains("apps/bistromath1")
                .contains("You are in a Bistromath app at `apps/bistromath1`.");
    }

    @Test
    void noActiveApp_rendersNoAppBlock() {
        assertThat(render(PromptContextBuilder.create().build()))
                .doesNotContain("implied target")
                .doesNotContain("bound to this task");
    }

    @Test
    void boundDocument_andSelection_areNamed() {
        String out = render(PromptContextBuilder.create()
                .cortexBoundDoc("apps/bistromath1/main.js")
                .cortexBoundDocSelection(new BoundDocSelection(10, 20))
                .build());

        assertThat(out).contains("apps/bistromath1/main.js").contains("10:20");
    }

    @Test
    void boundDocumentWithoutSelection_omitsTheSelectionSentence() {
        String out = render(PromptContextBuilder.create()
                .cortexBoundDoc("apps/bistromath1/main.js")
                .build());

        assertThat(out).contains("apps/bistromath1/main.js")
                .doesNotContain("doc_get_selection");
    }

    private String render(Map<String, Object> context) {
        return renderer.render(prompt(), context);
    }

    private static String prompt() {
        String path = "vance-defaults/_vance/prompts/frankie-prompt.md";
        try (var in = FrankiePromptClientContextTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing bundled prompt: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
