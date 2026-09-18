package de.mhus.vance.addon.brain.scribble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.addon.brain.scribble.model.ScribblePoint;
import de.mhus.vance.addon.brain.scribble.model.ScribbleSheet;
import de.mhus.vance.addon.brain.scribble.model.ScribbleSize;
import de.mhus.vance.addon.brain.scribble.model.ScribbleStroke;
import de.mhus.vance.shared.document.kind.KindCodecException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScribbleCodecTest {

    private static final String YAML = "application/yaml";
    private static final String JSON = "application/json";

    private static ScribbleSheet sample() {
        ScribbleStroke black = new ScribbleStroke(
                ScribbleStroke.Tool.PEN,
                "1",
                ScribbleStroke.Width.M,
                List.of(new ScribblePoint(42, 80, 0.4), new ScribblePoint(47, 81, 0.6)));
        ScribbleStroke red = new ScribbleStroke(
                ScribbleStroke.Tool.PEN,
                "2",
                ScribbleStroke.Width.S,
                List.of(new ScribblePoint(42, 140, 0.3), new ScribblePoint(44, 141, 0.3)));
        return new ScribbleSheet("Meeting", new ScribbleSize(1240, 1754), List.of(black, red));
    }

    @Test
    void yamlRoundTrip_preservesSheetAndStrokes() {
        ScribbleSheet back = ScribbleCodec.parse(ScribbleCodec.serialize(sample(), YAML), YAML);

        assertThat(back.title()).isEqualTo("Meeting");
        assertThat(back.size()).isEqualTo(new ScribbleSize(1240, 1754));
        assertThat(back.strokes()).hasSize(2);

        ScribbleStroke first = back.strokes().get(0);
        assertThat(first.tool()).isEqualTo(ScribbleStroke.Tool.PEN);
        assertThat(first.color()).isEqualTo("1");
        assertThat(first.width()).isEqualTo(ScribbleStroke.Width.M);
        assertThat(first.points()).containsExactly(new ScribblePoint(42, 80, 0.4), new ScribblePoint(47, 81, 0.6));

        ScribbleStroke second = back.strokes().get(1);
        assertThat(second.color()).isEqualTo("2");
        assertThat(second.width()).isEqualTo(ScribbleStroke.Width.S);
    }

    @Test
    void yaml_staysOneLinePerStroke() {
        // The size property of the format: a stroke renders inline, never as
        // one YAML line per coordinate. Without the flow-style dumper a full
        // written sheet would explode into tens of thousands of lines.
        String yaml = ScribbleCodec.serialize(sample(), YAML);

        assertThat(yaml).contains("size: {w: 1240, h: 1754}");
        // SnakeYAML quotes the palette index ('1') so it round-trips as a
        // string, never as an integer.
        assertThat(yaml)
                .containsPattern("\\{tool: pen, c: '1', w: m, p: \\[\\[42, 80, 0\\.4\\], \\[47, 81, 0\\.6\\]\\]\\}");
        long strokeLines = yaml.lines().filter(l -> l.contains("tool: pen")).count();
        assertThat(strokeLines).isEqualTo(2);
    }

    @Test
    void jsonRoundTrip_matchesYamlModel() {
        ScribbleSheet back = ScribbleCodec.parse(ScribbleCodec.serialize(sample(), JSON), JSON);
        assertThat(back.title()).isEqualTo("Meeting");
        assertThat(back.strokes()).hasSize(2);
        assertThat(back.strokes().get(0).points().get(1).pressure()).isEqualTo(0.6);
    }

    @Test
    void emptyBody_isEmptySheetWithA4Raster() {
        ScribbleSheet back = ScribbleCodec.parse("$meta:\n  kind: scribble\n", YAML);
        assertThat(back.title()).isNull();
        assertThat(back.size()).isEqualTo(ScribbleSize.a4Portrait());
        assertThat(back.strokes()).isEmpty();
    }

    @Test
    void unusableStrokes_areDroppedNotThrown() {
        String body = """
                $meta:
                  kind: scribble
                scribble:
                  size: {w: 100, h: 100}
                  strokes:
                  - {tool: pen, c: "1", w: m, p: [[1, 2, 0.5]]}
                  - {tool: pen, c: "1", w: m, p: []}
                  - {tool: pen, c: "1", w: m}
                """;
        ScribbleSheet back = ScribbleCodec.parse(body, YAML);
        assertThat(back.strokes()).hasSize(1);
        assertThat(back.strokes().get(0).points().get(0)).isEqualTo(new ScribblePoint(1, 2, 0.5));
    }

    @Test
    void lenientParsing_fallsBackToDefaultsAndSkipsJunkPoints() {
        String body = """
                $meta:
                  kind: scribble
                scribble:
                  strokes:
                  - {c: "9", w: xl, p: [[10, 20, 7], [1], "junk", [30, 40]]}
                """;
        ScribbleSheet back = ScribbleCodec.parse(body, YAML);

        assertThat(back.size()).isEqualTo(ScribbleSize.a4Portrait());
        assertThat(back.strokes()).hasSize(1);
        ScribbleStroke stroke = back.strokes().get(0);
        assertThat(stroke.tool()).isEqualTo(ScribbleStroke.Tool.PEN);
        assertThat(stroke.color()).isEqualTo("1"); // invalid palette index
        assertThat(stroke.width()).isEqualTo(ScribbleStroke.Width.M); // unknown width
        assertThat(stroke.points()).hasSize(2); // junk entries skipped
        assertThat(stroke.points().get(0).pressure()).isEqualTo(1.0); // clamped from 7
        assertThat(stroke.points().get(1).pressure()).isEqualTo(0.5); // missing pressure
    }

    @Test
    void invalidSize_fallsBackToA4() {
        String body = """
                $meta:
                  kind: scribble
                scribble:
                  size: {w: -5, h: 0}
                  strokes: []
                """;
        ScribbleSheet back = ScribbleCodec.parse(body, YAML);
        assertThat(back.size()).isEqualTo(ScribbleSize.a4Portrait());
    }

    @Test
    void unsupportedMimeType_throws() {
        assertThatThrownBy(() -> ScribbleCodec.serialize(sample(), "text/markdown"))
                .isInstanceOf(KindCodecException.class);
        assertThatThrownBy(() -> ScribbleCodec.parse("x", "text/markdown")).isInstanceOf(KindCodecException.class);
    }
}
