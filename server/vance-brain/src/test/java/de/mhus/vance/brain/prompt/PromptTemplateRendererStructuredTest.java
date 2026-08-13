package de.mhus.vance.brain.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the two newline policies of {@link PromptTemplateRenderer} against
 * each other. They are a deliberate pair, not an accident: prose wants the
 * newline after a tag gone, structured output needs it kept. A change to
 * either is a change to how every prompt or every template renders.
 */
class PromptTemplateRendererStructuredTest {

    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();

    @Test
    void render_trimsTheNewlineAfterATag() {
        // Pebble's default, kept for prompts: a `{% if %}` alone on a line
        // must not leave a blank line behind in the rendered prompt.
        assertThat(renderer.render("title: {{ topic }}\nnext\n", Map.of("topic", "X")))
                .isEqualTo("title: Xnext\n");
    }

    @Test
    void renderStructured_keepsTheNewlineAfterATag() {
        assertThat(renderer.renderStructured("title: {{ topic }}\nnext\n", Map.of("topic", "X")))
                .isEqualTo("title: X\nnext\n");
    }

    @Test
    void renderStructured_keepsYamlIndentationIntact() {
        String out = renderer.renderStructured(
                "a:\n  b: {{ v }}\n  c: 2\n", Map.of("v", "1"));
        assertThat(out).isEqualTo("a:\n  b: 1\n  c: 2\n");
    }

    @Test
    void renderStructured_leavesBlockTagsALine() {
        // The cost of keeping newlines: a block tag on its own line leaves a
        // blank one. Harmless in YAML / markdown — asserted so the trade-off
        // stays visible rather than being discovered later as a surprise.
        String out = renderer.renderStructured(
                "a: 1\n{% if true %}\nb: 2\n{% endif %}\n", Map.of());
        assertThat(out).isEqualTo("a: 1\n\nb: 2\n\n");
    }
}
