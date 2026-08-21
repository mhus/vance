package de.mhus.vance.addon.brain.links;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.document.kind.ApplicationCodec;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Guards the bundled {@code _vance/templates/links.tmpl.yaml}: it is Pebble,
 * so nothing in the normal build notices when an edit breaks the indentation
 * or a quote. Running the real renderer and then the real parser closes that
 * gap — otherwise the first person to pick the template is the test.
 */
class LinksTemplateTest {

    private static final String BODY = "vance-defaults/_vance/templates/links.tmpl.yaml";
    private static final String YAML_MIME = "application/yaml";

    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();

    @Test
    void rendered_isALinksManifest() {
        ApplicationDocument doc = parse(render(vars("Reading", "Long reads")));

        assertThat(doc.kind()).isEqualTo("application");
        assertThat(doc.app()).isEqualTo(LinksApplication.APP_NAME);
        assertThat(doc.title()).isEqualTo("Reading");
        assertThat(doc.description()).isEqualTo("Long reads");
    }

    @Test
    void rendered_parsesIntoTheConfigTheAppReads() {
        LinksConfig config = LinksConfig.from(parse(render(vars("Reading", null))));

        assertThat(config.entries()).isEmpty();
        assertThat(config.indexOutputPath()).isEqualTo(LinksConfig.DEFAULT_INDEX);
    }

    @Test
    void rendered_withoutADescriptionOmitsTheKeyRatherThanWritingNull() {
        assertThat(parse(render(vars("Reading", null))).description()).isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static Map<String, Object> vars(String title, String description) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", title);
        if (description != null) vars.put("description", description);
        return vars;
    }

    private String render(Map<String, Object> vars) {
        String rendered = renderer.renderStructured(templateBody(), vars);
        assertThat(rendered).isNotNull();
        return rendered;
    }

    private static ApplicationDocument parse(String body) {
        return ApplicationCodec.parse(body, YAML_MIME);
    }

    private static String templateBody() {
        try (InputStream in = LinksTemplateTest.class.getClassLoader()
                .getResourceAsStream(BODY)) {
            if (in == null) {
                throw new AssertionError("bundled template not on the classpath: " + BODY);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + BODY, e);
        }
    }
}
