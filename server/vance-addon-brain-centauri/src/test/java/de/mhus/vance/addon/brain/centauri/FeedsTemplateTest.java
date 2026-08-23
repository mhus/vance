package de.mhus.vance.addon.brain.centauri;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.centauri.FeedStream;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.document.kind.ApplicationCodec;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Guards the bundled {@code _vance/templates/feeds.tmpl.yaml}: rendering it must
 * produce a manifest that actually parses into a feed configuration, not merely
 * text that looks right.
 *
 * <p>A template is Pebble, so nothing in the normal build notices when an edit
 * breaks the YAML indentation or the comma handling of a list. Running the real
 * renderer and then the real parser closes that gap — otherwise the first person
 * to pick the template is the test.
 */
class FeedsTemplateTest {

    private static final String BODY = "vance-defaults/_vance/templates/feeds.tmpl.yaml";
    private static final String YAML_MIME = "application/yaml";

    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();

    @Test
    void rendered_isAFeedsManifest() {
        ApplicationDocument doc = parse(render(full()));

        assertThat(doc.kind()).isEqualTo("application");
        assertThat(doc.app()).isEqualTo("feeds");
        assertThat(doc.title()).isEqualTo("Morgenlage");
        assertThat(doc.description()).isEqualTo("Was über Nacht passiert ist");
    }

    @Test
    void rendered_parsesIntoTheConfiguration() {
        FeedsConfig config = FeedsConfig.from(parse(render(full())));

        // Streams stay empty on purpose: which sources exist depends on the
        // project's settings, which the create form cannot know.
        assertThat(config.streams()).isEmpty();
        assertThat(config.pageSize()).isEqualTo(20);
        assertThat(config.languages()).containsExactlyInAnyOrder("de", "en");
        assertThat(config.exclude()).containsExactly("krypto", "sport");
        assertThat(config.since()).isEqualTo("-7d");
    }

    @Test
    void rendered_relativeSinceResolvesAgainstNow() {
        FeedsConfig config = FeedsConfig.from(parse(render(full())));
        Instant now = Instant.parse("2026-08-19T12:00:00Z");

        // The point of storing it relative: it still means "last week" next month.
        assertThat(config.resolveSince(now)).isEqualTo(Instant.parse("2026-08-12T12:00:00Z"));
    }

    @Test
    void rendered_commaSeparatedListsAreTrimmed() {
        // "de ,  en" is what a person types; the config must not end up with a
        // language called " en" that matches nothing.
        Map<String, Object> ctx = full();
        ctx.put("languages", "de ,  en");

        assertThat(FeedsConfig.from(parse(render(ctx))).languages())
                .containsExactlyInAnyOrder("de", "en");
    }

    @Test
    void rendered_withOnlyTheRequiredField_isStillValid() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("title", "Nur Titel");

        FeedsConfig config = FeedsConfig.from(parse(render(ctx)));

        assertThat(config.languages()).isEmpty();
        assertThat(config.exclude()).isEmpty();
        assertThat(config.since()).isNull();
        assertThat(config.pageSize()).isEqualTo(20);
    }

    @Test
    void rendered_leavesNoUnsubstitutedPlaceholders() {
        assertThat(render(full())).doesNotContain("{{").doesNotContain("{%");
    }

    @Test
    void rendered_titleWithQuotesStaysValidYaml() {
        // Free text goes straight into a YAML scalar. Before the template
        // quoted defensively, `"Später" lesen` produced `title: ""Später"
        // lesen"` — a manifest that no longer parses, written without a
        // complaint because the application kind has no validate.
        String hostile = "\"Später\" lesen \\ it's fine";
        Map<String, Object> ctx = full();
        ctx.put("title", hostile);
        ctx.put("description", "a 'quoted' one");

        ApplicationDocument doc = parse(render(ctx));

        assertThat(doc.title()).isEqualTo(hostile);
        assertThat(doc.description()).isEqualTo("a 'quoted' one");
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static Map<String, Object> full() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("title", "Morgenlage");
        ctx.put("description", "Was über Nacht passiert ist");
        ctx.put("languages", "de, en");
        ctx.put("exclude", "krypto, sport");
        ctx.put("since", "-7d");
        return ctx;
    }

    private String render(Map<String, Object> context) {
        String rendered = renderer.renderStructured(templateBody(), context);
        assertThat(rendered).isNotNull();
        return rendered;
    }

    private static ApplicationDocument parse(String rendered) {
        return ApplicationCodec.parse(rendered, YAML_MIME);
    }

    private static String templateBody() {
        try (InputStream in = FeedsTemplateTest.class.getClassLoader()
                .getResourceAsStream(BODY)) {
            if (in == null) {
                throw new IllegalStateException("bundled template body missing: " + BODY);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + BODY, e);
        }
    }
}
