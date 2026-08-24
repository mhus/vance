package de.mhus.vance.addon.brain.zarniwoop;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.shared.form.FormFieldYamlParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Guards the bundled {@code _vance/templates/search.yaml} against drift from
 * {@link SearchApplication}.
 *
 * <p>The template used to carry a Pebble body that hand-wrote the manifest, and
 * this test rendered it to catch a broken indent or an unquoted title. The body
 * is gone: {@code app: search} routes the form through
 * {@code SearchApplication.create}, so the manifest is serialised by the codec
 * and can no longer be malformed. What remains breakable is the seam — the form
 * collects field names that {@code create()} looks up in its params map, and
 * nothing but this test connects the two. Rename a field on either side and the
 * app silently falls back to its defaults.
 */
class SearchTemplateTest {

    private static final String DEFINITION = "vance-defaults/_vance/templates/search.yaml";

    @Test
    void definition_routesThroughTheApplication() {
        Map<String, Object> spec = spec();

        assertThat(spec.get("app")).isEqualTo(SearchApplication.APP_NAME);
        // The application owns filename and MIME; declaring either is refused
        // at load time by TemplateLoader.
        assertThat(spec).doesNotContainKeys("name", "type");
    }

    @Test
    void definition_fieldsAreTheParamsCreateReads() {
        // SearchApplication.create reads exactly these keys out of ctx.params().
        assertThat(fields()).extracting(FormFieldDto::getName)
                .containsExactly("title", "description", "defaultModality", "defaultNum");
    }

    @Test
    void definition_resultCountStaysATypedInteger() {
        // create() reads the count with `instanceof Number`. The web form
        // submits every value as a string, and TemplateService only re-types
        // what the field declares — so an `integer` here is what makes the
        // typed count arrive at all. As `string` it would be dropped in favour
        // of the app's own default, without any error.
        FormFieldDto num = fields().stream()
                .filter(f -> "defaultNum".equals(f.getName()))
                .findFirst().orElseThrow();

        assertThat(num.getType()).isEqualTo("integer");
    }

    private static java.util.List<FormFieldDto> fields() {
        return FormFieldYamlParser.parseFields(spec().get("fields"), "fields");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> spec() {
        try (InputStream in = SearchTemplateTest.class.getClassLoader()
                .getResourceAsStream(DEFINITION)) {
            if (in == null) {
                throw new AssertionError("bundled template not on the classpath: " + DEFINITION);
            }
            return (Map<String, Object>) new Yaml()
                    .load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AssertionError("could not read " + DEFINITION, e);
        }
    }
}
