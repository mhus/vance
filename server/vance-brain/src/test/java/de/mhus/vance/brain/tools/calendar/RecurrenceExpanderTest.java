package de.mhus.vance.brain.tools.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.kind.CalendarEvent;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure-function tests for the RFC 5545 RRULE expansion subset.
 * The matching TypeScript implementation in {@code CalendarView.vue}
 * shares the same shape; if these tests change, the TS code probably
 * needs to follow.
 */
class RecurrenceExpanderTest {

    private static CalendarEvent ev(String id, String start, String end,
                                    boolean allDay, String recurrence) {
        return new CalendarEvent(
                id, "Test", start, end, allDay,
                null, List.of(), recurrence, null, List.of(), null,
                new LinkedHashMap<>());
    }

    // ── Non-recurring ──────────────────────────────────────────────

    @Test
    void nonRecurring_inWindow_yieldsOne() {
        CalendarEvent e = ev("e1", "2026-06-12T09:00:00", "2026-06-12T11:00:00",
                false, null);
        List<RecurrenceExpander.Occurrence> occs = RecurrenceExpander.expand(e,
                LocalDateTime.of(2026, 6, 1, 0, 0),
                LocalDateTime.of(2026, 6, 30, 23, 59));
        assertThat(occs).hasSize(1);
        assertThat(occs.get(0).startIso()).isEqualTo("2026-06-12T09:00");
    }

    @Test
    void nonRecurring_outsideWindow_yieldsNone() {
        CalendarEvent e = ev("e1", "2026-05-01T09:00:00", null, false, null);
        List<RecurrenceExpander.Occurrence> occs = RecurrenceExpander.expand(e,
                LocalDateTime.of(2026, 6, 1, 0, 0),
                LocalDateTime.of(2026, 6, 30, 23, 59));
        assertThat(occs).isEmpty();
    }

    // ── Daily ──────────────────────────────────────────────────────

    @Test
    void daily_until_yieldsBoundedSeries() {
        CalendarEvent e = ev("d1", "2026-06-01T09:00:00", "2026-06-01T09:15:00",
                false, "FREQ=DAILY;UNTIL=20260605T000000Z");
        List<RecurrenceExpander.Occurrence> occs = RecurrenceExpander.expand(e,
                LocalDateTime.of(2026, 6, 1, 0, 0),
                LocalDateTime.of(2026, 6, 30, 0, 0));
        // 1, 2, 3, 4 — UNTIL is exclusive past midnight on the 5th
        assertThat(occs).hasSize(4);
        assertThat(occs.get(0).startIso()).startsWith("2026-06-01");
        assertThat(occs.get(3).startIso()).startsWith("2026-06-04");
    }

    @Test
    void daily_count_yieldsExactlyNOccurrences() {
        CalendarEvent e = ev("d1", "2026-06-01T09:00:00", null,
                false, "FREQ=DAILY;COUNT=3");
        List<RecurrenceExpander.Occurrence> occs = RecurrenceExpander.expand(e,
                LocalDateTime.of(2026, 6, 1, 0, 0),
                LocalDateTime.of(2026, 6, 30, 0, 0));
        assertThat(occs).hasSize(3);
    }

    @Test
    void daily_interval_skipsDays() {
        CalendarEvent e = ev("d1", "2026-06-01T09:00:00", null,
                false, "FREQ=DAILY;INTERVAL=2;COUNT=4");
        List<RecurrenceExpander.Occurrence> occs = RecurrenceExpander.expand(e,
                LocalDateTime.of(2026, 6, 1, 0, 0),
                LocalDateTime.of(2026, 6, 30, 0, 0));
        assertThat(occs).hasSize(4);
        assertThat(occs.get(0).startIso()).startsWith("2026-06-01");
        assertThat(occs.get(1).startIso()).startsWith("2026-06-03");
        assertThat(occs.get(2).startIso()).startsWith("2026-06-05");
        assertThat(occs.get(3).startIso()).startsWith("2026-06-07");
    }

    // ── Weekly + BYDAY ────────────────────────────────────────────

    @Test
    void weekly_byday_expandsAcrossWeekdays() {
        // 2026-06-01 is a Monday. Standup MO,TU,WE,TH,FR for one week.
        CalendarEvent e = ev("s", "2026-06-01T09:00:00", "2026-06-01T09:15:00",
                false, "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;COUNT=5");
        List<RecurrenceExpander.Occurrence> occs = RecurrenceExpander.expand(e,
                LocalDateTime.of(2026, 6, 1, 0, 0),
                LocalDateTime.of(2026, 6, 30, 0, 0));
        assertThat(occs).hasSize(5);
        assertThat(occs.get(0).startIso()).startsWith("2026-06-01");
        assertThat(occs.get(4).startIso()).startsWith("2026-06-05");
    }

    @Test
    void weekly_byday_until_capsAtUntil() {
        CalendarEvent e = ev("s", "2026-06-01T09:00:00", "2026-06-01T09:15:00",
                false, "FREQ=WEEKLY;BYDAY=MO,WE,FR;UNTIL=20260615T000000Z");
        List<RecurrenceExpander.Occurrence> occs = RecurrenceExpander.expand(e,
                LocalDateTime.of(2026, 6, 1, 0, 0),
                LocalDateTime.of(2026, 6, 30, 0, 0));
        // Week of 06-01: Mo (1), We (3), Fr (5) - 3 occurrences
        // Week of 06-08: Mo (8), We (10), Fr (12) - 3 occurrences
        // = 6 total before UNTIL=06-15
        assertThat(occs).hasSize(6);
    }

    // ── Window clipping ───────────────────────────────────────────

    @Test
    void daily_windowClipping_returnsOnlyOccurrencesInRange() {
        CalendarEvent e = ev("d1", "2026-06-01T09:00:00", null,
                false, "FREQ=DAILY;COUNT=30");
        List<RecurrenceExpander.Occurrence> occs = RecurrenceExpander.expand(e,
                LocalDateTime.of(2026, 6, 10, 0, 0),
                LocalDateTime.of(2026, 6, 15, 23, 59));
        // 10, 11, 12, 13, 14, 15
        assertThat(occs).hasSize(6);
    }

    // ── Safety caps ───────────────────────────────────────────────

    @Test
    void unboundedRecurrence_capsAtRangeEnd() {
        // No UNTIL, no COUNT — would loop forever if unguarded. The
        // range-end check has to break it. Window goes to end-of-day
        // on the 5th so all five 09:00-occurrences fall inside.
        CalendarEvent e = ev("d", "2026-06-01T09:00:00", null,
                false, "FREQ=DAILY");
        List<RecurrenceExpander.Occurrence> occs = RecurrenceExpander.expand(e,
                LocalDateTime.of(2026, 6, 1, 0, 0),
                LocalDateTime.of(2026, 6, 5, 23, 59));
        assertThat(occs).hasSize(5);
    }

    // ── Malformed rules ───────────────────────────────────────────

    @Test
    void malformedRrule_fallsBackToSingleOccurrence() {
        CalendarEvent e = ev("x", "2026-06-12T09:00:00", null,
                false, "this is not a real rule");
        List<RecurrenceExpander.Occurrence> occs = RecurrenceExpander.expand(e,
                LocalDateTime.of(2026, 6, 1, 0, 0),
                LocalDateTime.of(2026, 6, 30, 0, 0));
        assertThat(occs).hasSize(1);
    }

    // ── All-day ───────────────────────────────────────────────────

    @Test
    void allDay_event_yieldsDateOnlyOccurrence() {
        CalendarEvent e = ev("v", "2026-07-15", "2026-07-28", true, null);
        List<RecurrenceExpander.Occurrence> occs = RecurrenceExpander.expand(e,
                LocalDateTime.of(2026, 7, 1, 0, 0),
                LocalDateTime.of(2026, 7, 31, 23, 59));
        assertThat(occs).hasSize(1);
        assertThat(occs.get(0).allDay()).isTrue();
        assertThat(occs.get(0).startIso()).isEqualTo("2026-07-15");
        assertThat(occs.get(0).endIso()).isEqualTo("2026-07-28");
    }

    // ── Parser ────────────────────────────────────────────────────

    @Test
    void parseRrule_acceptsLeadingPrefix() {
        RecurrenceExpander.RruleSpec s = RecurrenceExpander.parseRrule(
                "RRULE:FREQ=DAILY;COUNT=3");
        assertThat(s).isNotNull();
        assertThat(s.freq()).isEqualTo(RecurrenceExpander.Freq.DAILY);
        assertThat(s.count()).isEqualTo(3);
    }

    @Test
    void parseRrule_missingFreq_returnsNull() {
        assertThat(RecurrenceExpander.parseRrule("INTERVAL=2")).isNull();
    }
}
