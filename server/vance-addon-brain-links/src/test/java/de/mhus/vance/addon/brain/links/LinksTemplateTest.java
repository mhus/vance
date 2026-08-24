package de.mhus.vance.addon.brain.links;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.shared.form.FormFieldYamlParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Guards the bundled {@code _vance/templates/links.yaml} against drift from
 * {@link LinksApplication}.
 *
 * <p>The template used to carry a Pebble body that hand-wrote the manifest, and
 * this test rendered it to catch a broken indent or a title with a quote in it.
 * The body is gone: {@code app: links} routes the form through
 * {@code LinksApplication.create}, which builds a {@link LinksConfig} and lets
 * {@code LinksStore} serialise it — a title can no longer break the file
 * because it is never interpolated into YAML text. What stays breakable is the
 * seam: the form's field names have to be the keys {@code create()} reads.
 */
class LinksTemplateTest {

    private static final String DEFINITION = "vance-defaults/_vance/templates/links.yaml";

    @Test
    void definition_routesThroughTheApplication() {
        Map<String, Object> spec = spec();

        assertThat(spec.get("app")).isEqualTo(LinksApplication.APP_NAME);
        // The application owns filename and MIME; declaring either is refused
        // at load time by TemplateLoader.
        assertThat(spec).doesNotContainKeys("name", "type");
    }

    @Test
    void definition_fieldsAreTheParamsCreateReads() {
        // create() reads title/description; groups are added in the app itself.
        assertThat(fields()).extracting(FormFieldDto::getName)
                .containsExactly("title", "description");
        assertThat(fields().get(0).isRequired()).isTrue();
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static List<FormFieldDto> fields() {
        return FormFieldYamlParser.parseFields(spec().get("fields"), "fields");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> spec() {
        try (InputStream in = LinksTemplateTest.class.getClassLoader()
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
