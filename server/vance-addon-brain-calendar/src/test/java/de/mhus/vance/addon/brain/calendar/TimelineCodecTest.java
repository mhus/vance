package de.mhus.vance.addon.brain.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.shared.document.kind.KindCodecException;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Round-trip and edge-case behaviour for {@code kind: timeline}.
 *
 * <p>Positions are pass-through strings, so the tests assert the
 * wrapper structure (axis declaration, lanes, entries) plus the
 * invariants the codec does enforce: {@code title} + a start position
 * required, missing ids auto-filled, aliases normalised, plain numbers
 * emitted unquoted, unknown keys preserved.
 */
class TimelineCodecTest {

    private static final String JSON_MIME = "application/json";
    private static final String YAML_MIME = "application/yaml";

    // ── parse ─────────────────────────────────────────────────────

    @Test
    void parseYaml_geologicalPeriodsOnAnAgoAxis() {
        String body = """
                $meta:
                  kind: timeline
                title: Mesozoikum
                axis:
                  mode: numeric
                  unit: Ma
                  direction: ago
                  label: Millionen Jahre vor heute
                lanes:
                  - id: stratigraphie
                    title: Stratigraphie
                  - id: fauna
                entries:
                  - id: jura
                    title: Jura
                    from: 201.4
                    to: 143.1
                    lane: stratigraphie
                  - id: oberjura
                    title: Oberjura
                    from: 161.5
                    to: 143.1
                    parent: jura
                    lane: stratigraphie
                """;

        TimelineDocument doc = TimelineCodec.parse(body, YAML_MIME);

        assertThat(doc.kind()).isEqualTo("timeline");
        assertThat(doc.title()).isEqualTo("Mesozoikum");
        assertThat(doc.axis().mode()).isEqualTo(TimelineAxis.TimelineAxisMode.NUMERIC);
        assertThat(doc.axis().unit()).isEqualTo("Ma");
        assertThat(doc.axis().direction()).isEqualTo(TimelineAxis.TimelineDirection.AGO);
        assertThat(doc.axis().label()).isEqualTo("Millionen Jahre vor heute");
        assertThat(doc.lanes()).extracting(TimelineLane::id)
                .containsExactly("stratigraphie", "fauna");
        assertThat(doc.lanes().get(1).displayTitle()).isEqualTo("fauna");
        assertThat(doc.entries()).hasSize(2);
        assertThat(doc.entries().get(0).from()).isEqualTo("201.4");
        assertThat(doc.entries().get(0).isPeriod()).isTrue();
        assertThat(doc.entries().get(1).parent()).isEqualTo("jura");
    }

    @Test
    void parseYaml_pointEntryHasNoEnd() {
        String body = """
                $meta:
                  kind: timeline
                axis:
                  mode: datetime
                entries:
                  - title: Schuss
                    from: "2026-03-04T21:47"
                """;

        TimelineDocument doc = TimelineCodec.parse(body, YAML_MIME);

        TimelineEntry entry = doc.entries().get(0);
        assertThat(entry.isPeriod()).isFalse();
        assertThat(entry.to()).isNull();
    }

    @Test
    void parseYaml_atAndEndAreReadAsFromAndTo() {
        // Models reach for `at:` on a point and `end:` on a period; the
        // alternative to accepting them is an entry that silently
        // vanishes.
        String body = """
                $meta:
                  kind: timeline
                axis:
                  mode: datetime
                entries:
                  - title: Anruf
                    at: "2026-03-04T21:40"
                  - title: Brand
                    start: "2026-03-04T22:10"
                    end: "2026-03-04T23:30"
                """;

        TimelineDocument doc = TimelineCodec.parse(body, YAML_MIME);

        assertThat(doc.entries().get(0).from()).isEqualTo("2026-03-04T21:40");
        assertThat(doc.entries().get(1).from()).isEqualTo("2026-03-04T22:10");
        assertThat(doc.entries().get(1).to()).isEqualTo("2026-03-04T23:30");
        // …and the aliases do not also leak into extra.
        assertThat(doc.entries().get(0).extra()).isEmpty();
        assertThat(doc.entries().get(1).extra()).isEmpty();
    }

    @Test
    void parseYaml_uncertaintyBoundsSurvive() {
        String body = """
                $meta:
                  kind: timeline
                axis:
                  mode: datetime
                entries:
                  - title: Opfer zuletzt gesehen
                    from: "2026-03-04T21:40"
                    fromLatest: "2026-03-04T22:05"
                """;

        TimelineEntry entry = TimelineCodec.parse(body, YAML_MIME).entries().get(0);

        assertThat(entry.hasUncertainty()).isTrue();
        assertThat(entry.fromLatest()).isEqualTo("2026-03-04T22:05");
        assertThat(entry.fromEarliest()).isNull();
    }

    @Test
    void parseYaml_lanesAsPlainIdList() {
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: numeric }
                lanes: [taeter, opfer, zeuge]
                entries:
                  - title: X
                    from: 1
                """;

        assertThat(TimelineCodec.parse(body, YAML_MIME).lanes())
                .extracting(TimelineLane::id)
                .containsExactly("taeter", "opfer", "zeuge");
    }

    @Test
    void parseYaml_lanesAsMapKeepInsertionOrder() {
        // The shape `_app.yaml` uses for the calendar application — a
        // model that has seen one reproduces it here.
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: numeric }
                lanes:
                  design:  { title: Design, color: blue }
                  backend: { title: Backend }
                entries:
                  - title: X
                    from: 1
                """;

        List<TimelineLane> lanes = TimelineCodec.parse(body, YAML_MIME).lanes();

        assertThat(lanes).extracting(TimelineLane::id).containsExactly("design", "backend");
        assertThat(lanes.get(0).color()).isEqualTo("blue");
    }

    @Test
    void parseYaml_entriesWithoutTitleOrStartAreDropped() {
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: numeric }
                entries:
                  - title: Keeps
                    from: 5
                  - from: 7
                  - title: No position
                  - not: an entry object
                """;

        assertThat(TimelineCodec.parse(body, YAML_MIME).entries())
                .extracting(TimelineEntry::title)
                .containsExactly("Keeps");
    }

    @Test
    void parseYaml_missingIdIsAutoFilled() {
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: numeric }
                entries:
                  - title: Anonymous
                    from: 3
                """;

        assertThat(TimelineCodec.parse(body, YAML_MIME).entries().get(0).id())
                .isNotBlank();
    }

    @Test
    void parseYaml_unquotedDateSurvivesTheYamlTagResolver() {
        // SnakeYAML promotes an unquoted ISO date to java.util.Date;
        // without coercion the entry would be dropped as positionless.
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: datetime }
                entries:
                  - title: Mondlandung
                    from: 1969-07-20
                """;

        assertThat(TimelineCodec.parse(body, YAML_MIME).entries().get(0).from())
                .isEqualTo("1969-07-20");
    }

    @Test
    void parseYaml_missingAxisFallsBackToAForwardNumericLine() {
        String body = """
                $meta:
                  kind: timeline
                entries:
                  - title: X
                    from: 1
                """;

        TimelineAxis axis = TimelineCodec.parse(body, YAML_MIME).axis();

        assertThat(axis.mode()).isEqualTo(TimelineAxis.TimelineAxisMode.NUMERIC);
        assertThat(axis.direction()).isEqualTo(TimelineAxis.TimelineDirection.FORWARD);
    }

    @Test
    void parseYaml_unknownAxisModeFallsBackInsteadOfFailing() {
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: geological }
                entries:
                  - title: X
                    from: 1
                """;

        assertThat(TimelineCodec.parse(body, YAML_MIME).axis().mode())
                .isEqualTo(TimelineAxis.TimelineAxisMode.NUMERIC);
    }

    @Test
    void parseJson_readsTheSameShape() {
        String body = """
                {
                  "$meta": { "kind": "timeline" },
                  "axis": { "mode": "numeric", "unit": "Ma", "direction": "ago" },
                  "entries": [
                    { "id": "jura", "title": "Jura", "from": "201.4", "to": "143.1" }
                  ]
                }
                """;

        TimelineDocument doc = TimelineCodec.parse(body, JSON_MIME);

        assertThat(doc.axis().direction()).isEqualTo(TimelineAxis.TimelineDirection.AGO);
        assertThat(doc.entries().get(0).from()).isEqualTo("201.4");
    }

    @Test
    void parse_rejectsMarkdown() {
        assertThatThrownBy(() -> TimelineCodec.parse("# nope", "text/markdown"))
                .isInstanceOf(KindCodecException.class)
                .hasMessageContaining("Unsupported mime type");
    }

    @Test
    void parse_blankBodyIsAnEmptyTimeline() {
        assertThat(TimelineCodec.parse("", YAML_MIME).entries()).isEmpty();
    }

    // ── serialize ─────────────────────────────────────────────────

    @Test
    void serializeYaml_emitsPlainNumbersUnquoted() {
        TimelineDocument doc = new TimelineDocument(
                "timeline", null,
                new TimelineAxis(
                        TimelineAxis.TimelineAxisMode.NUMERIC, "Ma",
                        TimelineAxis.TimelineDirection.AGO,
                        null, null, null, new LinkedHashMap<>()),
                List.of(),
                List.of(entry("jura", "Jura", "201.4", "143.1")),
                new LinkedHashMap<>());

        String yaml = TimelineCodec.serialize(doc, YAML_MIME);

        assertThat(yaml).contains("from: 201.4").contains("to: 143.1");
        assertThat(yaml).doesNotContain("'201.4'");
    }

    @Test
    void serializeYaml_keepsTrailingZeroesBecauseTheRoundTripIsTextual() {
        TimelineDocument doc = new TimelineDocument(
                "timeline", null, TimelineAxis.defaults(), List.of(),
                List.of(entry("x", "X", "201.40", null)), new LinkedHashMap<>());

        assertThat(TimelineCodec.serialize(doc, YAML_MIME)).contains("from: 201.40");
    }

    @Test
    void serializeYaml_wholeNumbersStayWholeAcrossSaves() {
        // A float-tagged whole number comes out as `1969.0`, and the
        // drift compounds: every save of a hand-written `from: 5`
        // would rewrite the author's file.
        TimelineDocument doc = new TimelineDocument(
                "timeline", null, TimelineAxis.defaults(), List.of(),
                List.of(entry("x", "X", "5", "1969")), new LinkedHashMap<>());

        String yaml = TimelineCodec.serialize(doc, YAML_MIME);

        assertThat(yaml).contains("from: 5").contains("to: 1969");
        TimelineEntry reparsed = TimelineCodec.parse(yaml, YAML_MIME).entries().get(0);
        assertThat(reparsed.from()).isEqualTo("5");
        assertThat(reparsed.to()).isEqualTo("1969");
    }

    @Test
    void serializeYaml_isoPositionsSurviveTheRoundTripVerbatim() {
        // Whether SnakeYAML quotes a given ISO shape is its business —
        // a date-only value resolves to its timestamp tag and gets
        // quoted, a minute-precision one does not. What must hold is
        // that both come back as the string that was written, including
        // the ones the tag resolver would otherwise turn into a Date.
        for (String position : List.of(
                "2026-03-04T21:47", "2026-03-04T21:47:30", "2026-03-04", "1969")) {
            TimelineDocument doc = new TimelineDocument(
                    "timeline", null,
                    new TimelineAxis(
                            TimelineAxis.TimelineAxisMode.DATETIME, null,
                            TimelineAxis.TimelineDirection.FORWARD,
                            null, null, null, new LinkedHashMap<>()),
                    List.of(),
                    List.of(entry("x", "Schuss", position, null)),
                    new LinkedHashMap<>());

            String yaml = TimelineCodec.serialize(doc, YAML_MIME);

            assertThat(TimelineCodec.parse(yaml, YAML_MIME).entries().get(0).from())
                    .as("round-trip of %s", position)
                    .isEqualTo(position);
        }
    }

    @Test
    void serializeYaml_omitsTheDefaultDirection() {
        String yaml = TimelineCodec.serialize(
                new TimelineDocument(
                        "timeline", null, TimelineAxis.defaults(), List.of(),
                        List.of(entry("x", "X", "1", null)), new LinkedHashMap<>()),
                YAML_MIME);

        assertThat(yaml).contains("mode: numeric").doesNotContain("direction:");
    }

    @Test
    void roundTrip_yaml_preservesAxisLanesEntriesAndExtras() {
        String body = """
                $meta:
                  kind: timeline
                title: Tathergang
                axis:
                  mode: datetime
                  label: Nacht vom 4. auf den 5. März
                lanes:
                  - id: taeter
                    title: Täter
                    color: red
                entries:
                  - id: e1
                    title: Anruf
                    from: '2026-03-04T21:40'
                    fromLatest: '2026-03-04T22:05'
                    lane: taeter
                    tags:
                    - beleg
                    notes: Mobilfunkzelle Nord
                    confidence: mittel
                sources: 3
                """;

        TimelineDocument first = TimelineCodec.parse(body, YAML_MIME);
        String out = TimelineCodec.serialize(first, YAML_MIME);
        TimelineDocument second = TimelineCodec.parse(out, YAML_MIME);

        assertThat(second.title()).isEqualTo("Tathergang");
        assertThat(second.axis().label()).isEqualTo("Nacht vom 4. auf den 5. März");
        assertThat(second.lanes()).isEqualTo(first.lanes());
        assertThat(second.entries()).isEqualTo(first.entries());
        assertThat(second.extra()).containsEntry("sources", 3);
        assertThat(second.entries().get(0).extra()).containsEntry("confidence", "mittel");
    }

    @Test
    void roundTrip_json_isStableToo() {
        String body = """
                {
                  "$meta": { "kind": "timeline" },
                  "axis": { "mode": "numeric", "unit": "Ma", "direction": "ago" },
                  "entries": [ { "id": "jura", "title": "Jura", "from": "201.4",
                                 "to": "143.1", "tags": ["mesozoikum"] } ]
                }
                """;

        TimelineDocument first = TimelineCodec.parse(body, JSON_MIME);
        TimelineDocument second =
                TimelineCodec.parse(TimelineCodec.serialize(first, JSON_MIME), JSON_MIME);

        assertThat(second.entries()).isEqualTo(first.entries());
        assertThat(second.axis()).isEqualTo(first.axis());
    }

    private static TimelineEntry entry(String id, String title, String from, String to) {
        return new TimelineEntry(
                id, title, from, to, null, null, null, null,
                null, null, null, List.of(), null, new LinkedHashMap<>());
    }
}
