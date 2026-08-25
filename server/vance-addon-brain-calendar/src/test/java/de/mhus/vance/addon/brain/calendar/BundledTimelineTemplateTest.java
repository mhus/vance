package de.mhus.vance.addon.brain.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.document.kind.validate.DocRefs;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Guards the bundled {@code timeline} document template: rendering it must
 * produce a document that opens, not just text that looks right.
 *
 * <p>A template is Pebble text, so nothing in the normal build notices when
 * a branch breaks the YAML indentation or an example forgets that an
 * {@code ago} axis runs from the larger number down. Running the real
 * renderer, then the real codec, then the real validator closes that gap.
 */
class BundledTimelineTemplateTest {

    private static final String BODY = "vance-defaults/_vance/templates/timeline.tmpl.yaml";
    private static final String YAML = "application/yaml";

    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();
    private final TimelineKindHandler handler = new TimelineKindHandler();

    private static final DocRefs NO_REFS = new DocRefs() {
        @Override public boolean exists(String path) { return false; }
        @Override public @Nullable String kindOf(String path) { return null; }
        @Override public @Nullable Map<String, Object> readYaml(String path) { return null; }
    };

    @Test
    void datetimeAxis_rendersAValidTimeline() {
        String rendered = render(Map.of(
                "title", "Tathergang",
                "mode", "datetime",
                "date", "2026-03-04",
                "lanes", "taeter, opfer, zeuge"));

        assertThat(rendered).doesNotContain("{{").doesNotContain("{%");

        TimelineDocument doc = TimelineCodec.parse(rendered, YAML);
        assertThat(doc.title()).isEqualTo("Tathergang");
        assertThat(doc.axis().mode()).isEqualTo(TimelineAxis.TimelineAxisMode.DATETIME);
        assertThat(doc.lanes()).extracting(TimelineLane::id)
                .containsExactly("taeter", "opfer", "zeuge");
        assertThat(doc.entries()).hasSize(2);
        assertThat(doc.entries().get(0).isPeriod()).isTrue();
        assertThat(doc.entries().get(1).isPeriod()).isFalse();
        assertThat(validate(rendered)).isEmpty();
    }

    @Test
    void agoAxis_rendersPeriodsCountingBackwards() {
        // The example has to model the rule it teaches: on an `ago` axis
        // the start is the larger number. Getting this wrong in the
        // template teaches every user the inverted form.
        String rendered = render(Map.of(
                "title", "Mesozoikum",
                "mode", "numeric",
                "unit", "Ma",
                "ago", true,
                "label", "Millionen Jahre vor heute"));

        TimelineDocument doc = TimelineCodec.parse(rendered, YAML);

        assertThat(doc.axis().direction()).isEqualTo(TimelineAxis.TimelineDirection.AGO);
        assertThat(doc.axis().unit()).isEqualTo("Ma");
        assertThat(doc.axis().label()).isEqualTo("Millionen Jahre vor heute");
        TimelineEntry period = doc.entries().get(0);
        assertThat(Double.parseDouble(period.from()))
                .isGreaterThan(Double.parseDouble(period.to()));
        assertThat(validate(rendered)).isEmpty();
    }

    @Test
    void forwardNumericAxis_rendersAValidTimeline() {
        String rendered = render(Map.of(
                "title", "Ablauf",
                "mode", "numeric",
                "unit", "min"));

        TimelineDocument doc = TimelineCodec.parse(rendered, YAML);

        assertThat(doc.axis().direction()).isEqualTo(TimelineAxis.TimelineDirection.FORWARD);
        assertThat(validate(rendered)).isEmpty();
    }

    @Test
    void noLanes_stillProducesAValidTimeline() {
        String rendered = render(Map.of("title", "Solo", "mode", "datetime", "date", "2026-03-04"));

        assertThat(TimelineCodec.parse(rendered, YAML).lanes()).isEmpty();
        assertThat(validate(rendered)).isEmpty();
    }

    @Test
    void emptyFormValues_stillProduceParsableYaml() {
        // strictVariables is off, so a missing value renders as empty. The
        // document must degrade to "incomplete", never to "malformed".
        String rendered = renderer.renderStructured(templateBody(), Map.of());

        assertThat(TimelineCodec.parse(rendered, YAML).entries()).isNotEmpty();
    }

    private String render(Map<String, Object> values) {
        return renderer.renderStructured(templateBody(), values);
    }

    private List<Finding> validate(String rendered) {
        return handler.validate(
                rendered,
                new KindValidationContext("t", "p", "x.timeline.yaml", YAML, NO_REFS));
    }

    private static String templateBody() {
        try (InputStream in = BundledTimelineTemplateTest.class.getClassLoader()
                .getResourceAsStream(BODY)) {
            if (in == null) throw new IllegalStateException("bundled template missing: " + BODY);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + BODY, e);
        }
    }
}
