package de.mhus.vance.addon.brain.contacts;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The bundled template renders to a board that parses and validates clean. */
class ContactBoardTemplateTest {

    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();

    @Test
    void rendersAnEmptyValidBoard() throws IOException {
        String body;
        try (InputStream in = getClass()
                .getClassLoader()
                .getResourceAsStream("vance-defaults/_vance/templates/contact-board.tmpl.yaml")) {
            assertThat(in).isNotNull();
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String rendered = renderer.renderStructured(body, Map.of("title", "Vertrag \"Meridian\""));

        assertThat(ContactBoardCodec.parse(rendered).title()).isEqualTo("Vertrag \"Meridian\"");
        assertThat(ContactBoardCodec.parse(rendered).pins()).isEmpty();
        assertThat(ContactBoardCodec.validate(rendered, "x")).isEmpty();
    }
}
