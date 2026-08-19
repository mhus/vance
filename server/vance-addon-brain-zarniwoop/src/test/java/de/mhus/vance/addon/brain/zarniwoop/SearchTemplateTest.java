package de.mhus.vance.addon.brain.zarniwoop;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.document.kind.ApplicationCodec;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
import de.mhus.vance.toolpack.research.SearchModality;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Guards the bundled {@code _vance/templates/search.tmpl.yaml}: rendering it has
 * to produce a manifest that actually parses into a search configuration, not
 * merely text that looks right.
 *
 * <p>A template is Pebble, so nothing in the normal build notices when an edit
 * breaks the YAML indentation or a quote. Running the real renderer and then the
 * real parser closes that gap — otherwise the first person to pick the template
 * is the test.
 */
class SearchTemplateTest {

    private static final String BODY = "vance-defaults/_vance/templates/search.tmpl.yaml";
    private static final String YAML_MIME = "application/yaml";

    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();

    @Test
    void rendered_isASearchManifest() {
        ApplicationDocument doc = parse(render(full()));

        assertThat(doc.kind()).isEqualTo("application");
        assertThat(doc.app()).isEqualTo(SearchApplication.APP_NAME);
        assertThat(doc.title()).isEqualTo("Marktbeobachtung");
        assertThat(doc.description()).contains("Zölle");
    }

    @Test
    void rendered_parsesIntoTheConfigTheAppReads() {
        // The whole point: the template's output has to survive SearchConfig,
        // not just look like YAML.
        SearchConfig config = SearchConfig.from(parse(render(full())));

        assertThat(config.defaultModality()).isEqualTo(SearchModality.NEWS);
        assertThat(config.defaultNum()).isEqualTo(20);
    }

    @Test
    void rendered_withOnlyATitleStillParses() {
        // Every field but the title is optional in the form, so the minimal case
        // is the one a person is most likely to produce.
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", "Suche");

        SearchConfig config = SearchConfig.from(parse(render(vars)));

        assertThat(config.defaultModality()).isEqualTo(SearchModality.WEB);
        assertThat(config.defaultNum()).isEqualTo(10);
    }

    @Test
    void rendered_withoutADescriptionOmitsTheKeyRatherThanWritingNull() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", "Suche");

        assertThat(parse(render(vars)).description()).isNull();
    }

    @Test
    void rendered_numberArrivesAsANumberNotAQuotedString() {
        // The form field is a string (that is what the form engine offers), so
        // the template has to hand YAML something it reads back as a number.
        // SearchConfig tolerates both, but a quoted count here would mean the
        // manifest and the app disagree about the type on every read.
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", "Suche");
        vars.put("defaultNum", "7");

        assertThat(render(vars)).contains("defaultNum: 7");
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static Map<String, Object> full() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", "Marktbeobachtung");
        vars.put("description", "Zölle und Lieferketten");
        vars.put("defaultModality", "news");
        vars.put("defaultNum", "20");
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
        try (InputStream in = SearchTemplateTest.class.getClassLoader()
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
