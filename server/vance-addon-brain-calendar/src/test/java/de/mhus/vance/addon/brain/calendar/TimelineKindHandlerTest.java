package de.mhus.vance.addon.brain.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.kind.validate.DocRefs;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The codec drops what it cannot read so one bad entry does not cost
 * the reader the other forty; the validator is where that trade is paid
 * back. These tests pin the three classes of problem it has to name:
 * entries that were dropped, positions unreadable on the declared
 * axis, and orderings that contradict themselves.
 */
class TimelineKindHandlerTest {

    private static final String YAML = "application/yaml";
    private final TimelineKindHandler handler = new TimelineKindHandler();

    private static final DocRefs NO_REFS = new DocRefs() {
        @Override public boolean exists(String path) { return false; }
        @Override public @Nullable String kindOf(String path) { return null; }
        @Override public @Nullable Map<String, Object> readYaml(String path) { return null; }
    };

    private static KindValidationContext ctx() {
        return new KindValidationContext("t", "p", "eras.timeline.yaml", YAML, NO_REFS);
    }

    private List<Finding> validate(String body) {
        return handler.validate(body, ctx());
    }

    private static List<String> codes(List<Finding> findings) {
        return findings.stream().map(Finding::code).toList();
    }

    // ── registration + detection ──────────────────────────────────

    @Test
    void kindName_isTimeline() {
        assertThat(handler.getName()).isEqualTo("timeline");
    }

    @Test
    void detects_claimsOnlyBodiesCarryingBothMarkerKeys() {
        String both = "axis:\n  mode: numeric\nentries:\n  - title: X\n    from: 1\n";
        String axisOnly = "axis:\n  mode: numeric\n";
        String entriesOnly = "entries:\n  - title: X\n";

        assertThat(handler.detects(both)).isTrue();
        assertThat(handler.detects(axisOnly)).isFalse();
        assertThat(handler.detects(entriesOnly)).isFalse();
    }

    // ── clean documents ───────────────────────────────────────────

    @Test
    void cleanAgoAxisTimeline_hasNoFindings() {
        String body = """
                $meta:
                  kind: timeline
                axis:
                  mode: numeric
                  unit: Ma
                  direction: ago
                lanes:
                  - id: strat
                entries:
                  - id: jura
                    title: Jura
                    from: 201.4
                    to: 143.1
                    lane: strat
                  - id: oberjura
                    title: Oberjura
                    from: 161.5
                    to: 143.1
                    parent: jura
                    lane: strat
                """;

        assertThat(validate(body)).isEmpty();
    }

    @Test
    void cleanDatetimeTimelineWithUncertainty_hasNoFindings() {
        String body = """
                $meta:
                  kind: timeline
                axis:
                  mode: datetime
                entries:
                  - title: Opfer zuletzt gesehen
                    from: '2026-03-04T21:40'
                    fromLatest: '2026-03-04T22:05'
                  - title: Brand
                    from: '2026-03-04T22:10'
                    to: '2026-03-04T23:00'
                    toLatest: '2026-03-04T23:30'
                """;

        assertThat(validate(body)).isEmpty();
    }

    // ── dropped entries ───────────────────────────────────────────

    @Test
    void reportsEntriesTheCodecDropped_byIndex() {
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: numeric }
                entries:
                  - title: Keeps
                    from: 1
                  - from: 7
                  - title: No position
                """;

        List<Finding> findings = validate(body);

        assertThat(codes(findings)).containsExactly(
                "timeline.entry.title-missing", "timeline.entry.from-missing");
        assertThat(findings).extracting(Finding::location)
                .containsExactly("entries[1]", "entries[2]");
        assertThat(findings).allMatch(f -> f.level() == Finding.Level.ERROR);
    }

    @Test
    void entriesThatAreNotAList_isAnError() {
        String body = "$meta:\n  kind: timeline\naxis: { mode: numeric }\nentries: nope\n";

        assertThat(codes(validate(body))).contains("timeline.entries-not-a-list");
    }

    // ── positions ─────────────────────────────────────────────────

    @Test
    void positionCarryingItsUnit_isReportedAgainstTheAxis() {
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: numeric, unit: Ma }
                entries:
                  - title: Jura
                    from: 201.4 Ma
                """;

        List<Finding> findings = validate(body);

        assertThat(codes(findings)).containsExactly("timeline.entry.position-unreadable");
        assertThat(findings.get(0).message())
                .contains("the unit lives in axis.unit");
    }

    @Test
    void isoPositionOnANumericAxis_isReported() {
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: numeric }
                entries:
                  - title: Mondlandung
                    from: '1969-07-20'
                """;

        assertThat(codes(validate(body))).containsExactly("timeline.entry.position-unreadable");
    }

    @Test
    void bareNumberOnADatetimeAxis_isReportedWithTheIsoHint() {
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: datetime }
                entries:
                  - title: Jura
                    from: 201.4
                """;

        List<Finding> findings = validate(body);

        assertThat(codes(findings)).containsExactly("timeline.entry.position-unreadable");
        assertThat(findings.get(0).message()).contains("ISO-8601");
    }

    // ── ordering ──────────────────────────────────────────────────

    @Test
    void periodEndingBeforeItStarts_isAnError() {
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: datetime }
                entries:
                  - title: Brand
                    from: '2026-03-04T23:00'
                    to: '2026-03-04T22:10'
                """;

        assertThat(codes(validate(body))).containsExactly("timeline.entry.reversed");
    }

    @Test
    void agoAxisPeriodWrittenForwards_isReportedWithTheAgoHint() {
        // The classic mistake: 143.1 → 201.4 reads like counting up, but
        // on an `ago` axis it means the Jurassic ends before it begins.
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: numeric, unit: Ma, direction: ago }
                entries:
                  - title: Jura
                    from: 143.1
                    to: 201.4
                """;

        List<Finding> findings = validate(body);

        assertThat(codes(findings)).containsExactly("timeline.entry.reversed");
        assertThat(findings.get(0).message()).contains("LARGER number");
    }

    @Test
    void agoAxisPeriodWrittenBackwards_isFine() {
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: numeric, unit: Ma, direction: ago }
                entries:
                  - title: Jura
                    from: 201.4
                    to: 143.1
                """;

        assertThat(validate(body)).isEmpty();
    }

    // ── uncertainty ───────────────────────────────────────────────

    @Test
    void uncertaintyWindowBesideItsOwnPoint_isAnError() {
        // A window drawn next to the point it qualifies renders a claim
        // the author never made.
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: datetime }
                entries:
                  - title: Anruf
                    from: '2026-03-04T21:40'
                    fromLatest: '2026-03-04T21:10'
                """;

        List<Finding> findings = validate(body);

        assertThat(codes(findings)).containsExactly("timeline.entry.uncertainty-window");
        assertThat(findings.get(0).message()).contains("has to contain");
    }

    @Test
    void invertedUncertaintyWindow_isReportedOnce() {
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: datetime }
                entries:
                  - title: Anruf
                    from: '2026-03-04T21:40'
                    fromEarliest: '2026-03-04T22:00'
                    fromLatest: '2026-03-04T21:00'
                """;

        assertThat(codes(validate(body)))
                .containsExactly("timeline.entry.uncertainty-window");
    }

    @Test
    void endBoundsWithoutAnEnd_isAWarning() {
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: datetime }
                entries:
                  - title: Anruf
                    from: '2026-03-04T21:40'
                    toLatest: '2026-03-04T22:00'
                """;

        List<Finding> findings = validate(body);

        assertThat(codes(findings))
                .containsExactly("timeline.entry.end-bounds-without-end");
        assertThat(findings.get(0).level()).isEqualTo(Finding.Level.WARNING);
    }

    @Test
    void agoAxisUncertainty_readsEarliestAsTheLargerNumber() {
        // 201.4 ± 0.2 Ma: the earlier bound is 201.6, the later 201.2.
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: numeric, unit: Ma, direction: ago }
                entries:
                  - title: Trias-Jura-Grenze
                    from: 201.4
                    fromEarliest: 201.6
                    fromLatest: 201.2
                """;

        assertThat(validate(body)).isEmpty();
    }

    // ── structure ─────────────────────────────────────────────────

    @Test
    void unknownParent_isAWarningNotAnError() {
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: numeric }
                entries:
                  - id: a
                    title: A
                    from: 1
                    parent: ghost
                """;

        List<Finding> findings = validate(body);

        assertThat(codes(findings)).containsExactly("timeline.entry.parent-unknown");
        assertThat(findings.get(0).level()).isEqualTo(Finding.Level.WARNING);
    }

    @Test
    void circularParentChain_isAnError() {
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: numeric }
                entries:
                  - id: a
                    title: A
                    from: 1
                    parent: b
                  - id: b
                    title: B
                    from: 2
                    parent: a
                """;

        assertThat(codes(validate(body)))
                .containsOnly("timeline.entry.parent-cycle");
    }

    @Test
    void selfParent_isAnError() {
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: numeric }
                entries:
                  - id: a
                    title: A
                    from: 1
                    parent: a
                """;

        assertThat(codes(validate(body))).containsExactly("timeline.entry.parent-cycle");
    }

    @Test
    void duplicateIds_areReportedOncePerId() {
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: numeric }
                entries:
                  - id: a
                    title: First
                    from: 1
                  - id: a
                    title: Second
                    from: 2
                """;

        assertThat(codes(validate(body))).containsExactly("timeline.entry.duplicate-id");
    }

    @Test
    void laneNotDeclared_isWarnedOncePerLane() {
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: numeric }
                lanes:
                  - id: strat
                entries:
                  - title: A
                    from: 1
                    lane: fauna
                  - title: B
                    from: 2
                    lane: fauna
                  - title: C
                    from: 3
                    lane: strat
                """;

        assertThat(codes(validate(body)))
                .containsExactly("timeline.entry.lane-undeclared");
    }

    @Test
    void undeclaredLaneWithoutAnyDeclaration_isNotWarned() {
        // Declaring lanes is optional; warning about a lane in a
        // document that declares none would fire on every simple file.
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: numeric }
                entries:
                  - title: A
                    from: 1
                    lane: fauna
                """;

        assertThat(validate(body)).isEmpty();
    }

    // ── axis ──────────────────────────────────────────────────────

    @Test
    void unknownAxisMode_warnsAndNamesTheFallback() {
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: geological }
                entries:
                  - title: A
                    from: 1
                """;

        List<Finding> findings = validate(body);

        assertThat(codes(findings)).containsExactly("timeline.axis.mode-unknown");
        assertThat(findings.get(0).message()).contains("numeric, datetime");
    }

    @Test
    void unknownAxisDirection_warns() {
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: numeric, direction: sideways }
                entries:
                  - title: A
                    from: 1
                """;

        assertThat(codes(validate(body))).containsExactly("timeline.axis.direction-unknown");
    }

    /**
     * The aliases are part of the wire, so the validator has to know them too.
     * It used to compare the declared word against the canonical one it parsed
     * to, and told the author their correctly-read document was unreadable.
     */
    @Test
    void documentedAxisAliases_areNotReportedAsUnknown() {
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: date, direction: backwards }
                entries:
                  - title: A
                    from: 1969-07-20
                """;

        assertThat(codes(validate(body)))
                .doesNotContain("timeline.axis.mode-unknown", "timeline.axis.direction-unknown");
    }

    @Test
    void unitOnADatetimeAxis_warnsThatItIsUnused() {
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: datetime, unit: Ma }
                entries:
                  - title: A
                    from: '2026-01-01'
                """;

        assertThat(codes(validate(body))).containsExactly("timeline.axis.unit-ignored");
    }

    @Test
    void agoAxisWindowWrittenForwards_warnsWithTheAgoHint() {
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: numeric, direction: ago, from: 100, to: 300 }
                entries:
                  - title: A
                    from: 200
                """;

        List<Finding> findings = validate(body);

        assertThat(codes(findings)).containsExactly("timeline.axis.bounds-reversed");
        assertThat(findings.get(0).message()).contains("LARGER number");
    }

    @Test
    void unreadableAxisBound_warns() {
        String body = """
                $meta:
                  kind: timeline
                axis: { mode: numeric, from: yesterday }
                entries:
                  - title: A
                    from: 1
                """;

        assertThat(codes(validate(body))).containsExactly("timeline.axis.bounds-unreadable");
    }

    // ── format ────────────────────────────────────────────────────

    @Test
    void markdownBody_isRejectedWithTheReasonNotJustTheRule() {
        List<Finding> findings = handler.validate(
                "# Erdzeitalter\n\n- Jura\n",
                new KindValidationContext("t", "p", "eras.md", "text/markdown", NO_REFS));

        assertThat(codes(findings)).containsExactly("timeline.mime");
        assertThat(findings.get(0).message()).contains("uncertainty bounds");
    }

    @Test
    void unsavedBufferWithoutAMimeType_isReadAsYaml() {
        String body = "axis:\n  mode: numeric\nentries:\n  - from: 1\n";

        List<Finding> findings = handler.validate(
                body, new KindValidationContext("t", "p", "draft", null, NO_REFS));

        assertThat(codes(findings)).containsExactly("timeline.entry.title-missing");
    }

    @Test
    void brokenYaml_reportsOneParseFindingInsteadOfThrowing() {
        List<Finding> findings = validate("axis: {{{\n");

        assertThat(codes(findings)).containsExactly("timeline.parse");
    }
}
