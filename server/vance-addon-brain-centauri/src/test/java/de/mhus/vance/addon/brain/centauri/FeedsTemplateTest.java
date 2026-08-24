package de.mhus.vance.addon.brain.centauri;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.shared.form.FormFieldYamlParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Guards the bundled {@code _vance/templates/feeds.yaml} together with the
 * mapping it feeds.
 *
 * <p>The template used to carry a Pebble body that hand-wrote the manifest, and
 * this test rendered it to catch a broken indent or an unquoted title. The body
 * is gone: {@code app: feeds} routes the form through
 * {@code FeedsApplication.create}, so the manifest is serialised by the codec
 * and can no longer be malformed. Two things stay breakable and are what this
 * test now covers: the form's field names have to be the keys
 * {@code fromParams} looks up, and the filter fields a person types as one
 * comma-separated line have to arrive as a trimmed list.
 */
class FeedsTemplateTest {

    private static final String DEFINITION = "vance-defaults/_vance/templates/feeds.yaml";

    @Test
    void definition_routesThroughTheApplication() {
        Map<String, Object> spec = spec();

        assertThat(spec.get("app")).isEqualTo(FeedsApplication.APP_NAME);
        // The application owns filename and MIME; declaring either is refused
        // at load time by TemplateLoader.
        assertThat(spec).doesNotContainKeys("name", "type");
    }

    @Test
    void definition_fieldsAreTheParamsCreateReads() {
        assertThat(fields()).extracting(FormFieldDto::getName)
                .containsExactly("title", "description", "languages", "exclude", "since");
    }

    @Test
    void params_becomeTheConfigurationTheAppReads() {
        FeedsConfig config = FeedsApplication.fromParams(full());

        // Streams stay empty on purpose: which sources exist depends on the
        // project's settings, which the create form cannot know.
        assertThat(config.streams()).isEmpty();
        assertThat(config.pageSize()).isEqualTo(20);
        assertThat(config.languages()).containsExactlyInAnyOrder("de", "en");
        assertThat(config.exclude()).containsExactly("krypto", "sport");
        assertThat(config.since()).isEqualTo("-7d");
    }

    @Test
    void params_relativeSinceResolvesAgainstNow() {
        FeedsConfig config = FeedsApplication.fromParams(full());
        Instant now = Instant.parse("2026-08-19T12:00:00Z");

        // The point of storing it relative: it still means "last week" next month.
        assertThat(config.resolveSince(now)).isEqualTo(Instant.parse("2026-08-12T12:00:00Z"));
    }

    @Test
    void params_commaSeparatedListsAreTrimmed() {
        // "de ,  en" is what a person types into the single text field; the
        // config must not end up with a language called " en" that matches
        // nothing.
        Map<String, Object> params = full();
        params.put("languages", "de ,  en");

        assertThat(FeedsApplication.fromParams(params).languages())
                .containsExactlyInAnyOrder("de", "en");
    }

    @Test
    void params_withOnlyTheRequiredField_areStillValid() {
        Map<String, Object> params = new HashMap<>();
        params.put("title", "Nur Titel");

        FeedsConfig config = FeedsApplication.fromParams(params);

        assertThat(config.languages()).isEmpty();
        assertThat(config.exclude()).isEmpty();
        assertThat(config.since()).isNull();
        assertThat(config.pageSize()).isEqualTo(20);
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static Map<String, Object> full() {
        Map<String, Object> params = new HashMap<>();
        params.put("title", "Morgenlage");
        params.put("description", "Was über Nacht passiert ist");
        params.put("languages", "de, en");
        params.put("exclude", "krypto, sport");
        params.put("since", "-7d");
        return params;
    }

    private static List<FormFieldDto> fields() {
        return FormFieldYamlParser.parseFields(spec().get("fields"), "fields");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> spec() {
        try (InputStream in = FeedsTemplateTest.class.getClassLoader()
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
