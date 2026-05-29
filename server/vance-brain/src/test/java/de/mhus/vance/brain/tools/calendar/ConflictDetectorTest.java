package de.mhus.vance.brain.tools.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.kind.CalendarEvent;
import de.mhus.vance.shared.document.kind.CalendarsAppConfig;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure-function tests for the time-overlap detector — no Spring,
 * no DocumentService. Verifies the basic geometric cases plus the
 * two filter knobs (ignoreWithinTags, ignoreAllDayOverlapsBetweenLanes).
 */
class ConflictDetectorTest {

    private static ConflictDetector.LocatedOccurrence occ(
            String title, String lane, LocalDateTime start, LocalDateTime end,
            boolean allDay, List<String> tags) {
        CalendarEvent ev = new CalendarEvent(
                "id-" + title, title, "x", null, allDay,
                null, List.of(), null, null, tags, null,
                new LinkedHashMap<>());
        return new ConflictDetector.LocatedOccurrence(
                new RecurrenceExpander.Occurrence(ev, start, end, allDay),
                lane, lane + "/" + title + ".yaml");
    }

    private static final CalendarsAppConfig.ConflictsConfig DEFAULT_CFG =
            CalendarsAppConfig.ConflictsConfig.defaults();

    // ── basic overlap ─────────────────────────────────────────────

    @Test
    void overlapping_timed_events_yieldConflict() {
        var a = occ("A", "design",
                LocalDateTime.of(2026, 6, 12, 9, 0),
                LocalDateTime.of(2026, 6, 12, 11, 0),
                false, List.of());
        var b = occ("B", "backend",
                LocalDateTime.of(2026, 6, 12, 10, 0),
                LocalDateTime.of(2026, 6, 12, 12, 0),
                false, List.of());
        List<ConflictDetector.Conflict> out = ConflictDetector.detect(
                List.of(a, b), DEFAULT_CFG);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).overlapStart()).isEqualTo(LocalDateTime.of(2026, 6, 12, 10, 0));
        assertThat(out.get(0).overlapEnd()).isEqualTo(LocalDateTime.of(2026, 6, 12, 11, 0));
    }

    @Test
    void backToBack_timed_events_doNotConflict() {
        var a = occ("A", "design",
                LocalDateTime.of(2026, 6, 12, 10, 0),
                LocalDateTime.of(2026, 6, 12, 11, 0),
                false, List.of());
        var b = occ("B", "design",
                LocalDateTime.of(2026, 6, 12, 11, 0),
                LocalDateTime.of(2026, 6, 12, 12, 0),
                false, List.of());
        assertThat(ConflictDetector.detect(List.of(a, b), DEFAULT_CFG)).isEmpty();
    }

    @Test
    void nonOverlapping_yieldNoConflict() {
        var a = occ("A", "x",
                LocalDateTime.of(2026, 6, 12, 9, 0),
                LocalDateTime.of(2026, 6, 12, 10, 0),
                false, List.of());
        var b = occ("B", "y",
                LocalDateTime.of(2026, 6, 12, 14, 0),
                LocalDateTime.of(2026, 6, 12, 15, 0),
                false, List.of());
        assertThat(ConflictDetector.detect(List.of(a, b), DEFAULT_CFG)).isEmpty();
    }

    @Test
    void threeWayOverlap_yieldsAllPairs() {
        var a = occ("A", "x",
                LocalDateTime.of(2026, 6, 12, 9, 0),
                LocalDateTime.of(2026, 6, 12, 12, 0),
                false, List.of());
        var b = occ("B", "y",
                LocalDateTime.of(2026, 6, 12, 10, 0),
                LocalDateTime.of(2026, 6, 12, 11, 0),
                false, List.of());
        var c = occ("C", "z",
                LocalDateTime.of(2026, 6, 12, 10, 30),
                LocalDateTime.of(2026, 6, 12, 11, 30),
                false, List.of());
        assertThat(ConflictDetector.detect(List.of(a, b, c), DEFAULT_CFG)).hasSize(3);
    }

    // ── ignoreWithinTags ──────────────────────────────────────────

    @Test
    void ignoreWithinTags_skipsPairWhereBothCarryTag() {
        var a = occ("Alice vacation", "private",
                LocalDateTime.of(2026, 7, 15, 0, 0),
                LocalDateTime.of(2026, 7, 28, 23, 59),
                true, List.of("private"));
        var b = occ("Bob vacation", "private",
                LocalDateTime.of(2026, 7, 20, 0, 0),
                LocalDateTime.of(2026, 7, 25, 23, 59),
                true, List.of("private"));
        var cfg = new CalendarsAppConfig.ConflictsConfig(
                "_conflicts.yaml", List.of("private"), false);
        assertThat(ConflictDetector.detect(List.of(a, b), cfg)).isEmpty();
    }

    @Test
    void ignoreWithinTags_keepsPairWhereOnlyOneHasTag() {
        // Only one carries the ignore tag — the conflict should still
        // surface (the LLM should warn the user).
        var a = occ("Vacation", "private",
                LocalDateTime.of(2026, 7, 15, 0, 0),
                LocalDateTime.of(2026, 7, 28, 23, 59),
                true, List.of("private"));
        var b = occ("Launch", "backend",
                LocalDateTime.of(2026, 7, 20, 14, 0),
                LocalDateTime.of(2026, 7, 20, 16, 0),
                false, List.of("milestone"));
        var cfg = new CalendarsAppConfig.ConflictsConfig(
                "_conflicts.yaml", List.of("private"), false);
        assertThat(ConflictDetector.detect(List.of(a, b), cfg)).hasSize(1);
    }

    // ── ignoreAllDayOverlapsBetweenLanes ──────────────────────────

    @Test
    void ignoreAllDayBetweenLanes_skipsCrossLanePair() {
        var a = occ("Design vacation", "design",
                LocalDateTime.of(2026, 7, 15, 0, 0),
                LocalDateTime.of(2026, 7, 20, 23, 59),
                true, List.of());
        var b = occ("Backend launch day", "backend",
                LocalDateTime.of(2026, 7, 17, 0, 0),
                LocalDateTime.of(2026, 7, 17, 23, 59),
                true, List.of());
        var cfg = new CalendarsAppConfig.ConflictsConfig(
                "_conflicts.yaml", List.of(), true);
        assertThat(ConflictDetector.detect(List.of(a, b), cfg)).isEmpty();
    }

    @Test
    void ignoreAllDayBetweenLanes_keepsSameLanePair() {
        var a = occ("Design vacation", "design",
                LocalDateTime.of(2026, 7, 15, 0, 0),
                LocalDateTime.of(2026, 7, 20, 23, 59),
                true, List.of());
        var b = occ("Design workshop", "design",
                LocalDateTime.of(2026, 7, 17, 0, 0),
                LocalDateTime.of(2026, 7, 17, 23, 59),
                true, List.of());
        var cfg = new CalendarsAppConfig.ConflictsConfig(
                "_conflicts.yaml", List.of(), true);
        assertThat(ConflictDetector.detect(List.of(a, b), cfg)).hasSize(1);
    }
}
