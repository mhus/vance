package de.mhus.vance.addon.brain.scribble;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.addon.brain.scribble.model.ScribblePoint;
import de.mhus.vance.addon.brain.scribble.model.ScribbleSheet;
import de.mhus.vance.addon.brain.scribble.model.ScribbleSize;
import de.mhus.vance.addon.brain.scribble.model.ScribbleStroke;
import de.mhus.vance.shared.document.kind.validate.DocRefs;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The handler is a thin adapter — the structural checks live in
 * {@link ScribbleValidationService}. Here we prove the full path through
 * the real codec: serialize a sheet → {@code validate(content)} parses it,
 * runs the checks and maps findings onto the shared vocabulary.
 */
class ScribbleKindHandlerTest {

    private static final String YAML = "application/yaml";
    private final ScribbleKindHandler handler = new ScribbleKindHandler();

    private static final DocRefs NO_REFS = new DocRefs() {
        @Override
        public boolean exists(String path) {
            return false;
        }

        @Override
        public @Nullable String kindOf(String path) {
            return null;
        }

        @Override
        public @Nullable Map<String, Object> readYaml(String path) {
            return null;
        }
    };

    private static KindValidationContext ctx() {
        return new KindValidationContext("t", "p", "notes.scribble.yaml", YAML, NO_REFS);
    }

    private static String yaml(List<ScribbleStroke> strokes) {
        return ScribbleCodec.serialize(new ScribbleSheet(null, ScribbleSize.a4Portrait(), strokes), YAML);
    }

    private static ScribbleStroke stroke(int x, int y) {
        return new ScribbleStroke(
                ScribbleStroke.Tool.PEN, "1", ScribbleStroke.Width.M, List.of(new ScribblePoint(x, y, 0.5)));
    }

    @Test
    void cleanSheet_hasNoFindings() {
        assertThat(handler.validate(yaml(List.of(stroke(10, 10), stroke(1230, 1740))), ctx()))
                .isEmpty();
    }

    @Test
    void unusableStroke_isReportedByIndex() {
        String body = """
                $meta:
                  kind: scribble
                scribble:
                  size: {w: 1240, h: 1754}
                  strokes:
                  - {tool: pen, c: "1", w: m, p: [[10, 10, 0.5]]}
                  - {tool: pen, c: "1", w: m, p: []}
                """;
        List<Finding> findings = handler.validate(body, ctx());
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).level()).isEqualTo(Finding.Level.ERROR);
        assertThat(findings.get(0).code()).isEqualTo("scribble-stroke-dropped");
        assertThat(findings.get(0).message()).contains("strokes[1]");
    }

    @Test
    void offRasterInk_isWarningNotError() {
        String body = yaml(List.of(stroke(5000, 9000)));
        List<Finding> findings = handler.validate(body, ctx());
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).level()).isEqualTo(Finding.Level.WARNING);
        assertThat(findings.get(0).code()).isEqualTo("scribble-bounds");
    }

    @Test
    void edgeOvershootWithinTolerance_isNotFlagged() {
        // Hands overshoot the page edge by a few points constantly — that
        // must not flood the validator.
        assertThat(handler.validate(yaml(List.of(stroke(-5, 1755))), ctx())).isEmpty();
    }

    @Test
    void unparseableContent_isScribbleParseError() {
        List<Finding> findings = handler.validate("\t : : not a scribble : [", ctx());
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).level()).isEqualTo(Finding.Level.ERROR);
        assertThat(findings.get(0).code()).isEqualTo("scribble-parse");
    }
}
