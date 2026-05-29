package de.mhus.vance.brain.tools.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.kind.CalendarDocument;
import de.mhus.vance.shared.document.kind.CalendarEvent;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link IcsExportService} — the inverse of
 * {@link IcsToCalendarTool#parseIcs}. Confirms wire-format correctness
 * for the cases real calendar apps care about: timed vs all-day,
 * UTC vs floating, RRULE round-trip, escape sequences, line folding.
 */
class IcsExportServiceTest {

    private final IcsExportService svc = new IcsExportService();

    private static CalendarEvent event(String id, String title, String start) {
        return new CalendarEvent(
                id, title, start, null, false,
                null, List.of(), null, null, List.of(), null,
                new LinkedHashMap<>());
    }

    // ── Header ─────────────────────────────────────────────────────

    @Test
    void toIcs_emitsStandardCalendarHeader() {
        String out = svc.toIcs(
                new CalendarDocument("calendar",
                        List.of(event("e", "X", "2026-06-12T09:00:00")),
                        new LinkedHashMap<>()),
                "My Calendar");
        assertThat(out).startsWith("BEGIN:VCALENDAR\r\n");
        assertThat(out).contains("VERSION:2.0\r\n");
        assertThat(out).contains("PRODID:-//Vance//Calendar//EN\r\n");
        assertThat(out).contains("CALSCALE:GREGORIAN\r\n");
        assertThat(out).contains("X-WR-CALNAME:My Calendar\r\n");
        assertThat(out).endsWith("END:VCALENDAR\r\n");
    }

    @Test
    void toIcs_omitsCalendarNameWhenAbsent() {
        String out = svc.toIcs(
                new CalendarDocument("calendar",
                        List.of(event("e", "X", "2026-06-12T09:00:00")),
                        new LinkedHashMap<>()),
                null);
        assertThat(out).doesNotContain("X-WR-CALNAME");
    }

    // ── Timed events ───────────────────────────────────────────────

    @Test
    void renderDateTime_offsetForm_normalisesToUtc() {
        assertThat(IcsExportService.renderDateTime("2026-06-12T09:00:00+02:00"))
                .isEqualTo("20260612T070000Z");
    }

    @Test
    void renderDateTime_utcForm_keepsZ() {
        assertThat(IcsExportService.renderDateTime("2026-06-12T09:00:00Z"))
                .isEqualTo("20260612T090000Z");
    }

    @Test
    void renderDateTime_localFormWithoutSeconds_emitsFloating() {
        assertThat(IcsExportService.renderDateTime("2026-06-12T09:00"))
                .isEqualTo("20260612T090000");
    }

    @Test
    void renderDateTime_localFormWithSeconds_emitsFloating() {
        assertThat(IcsExportService.renderDateTime("2026-06-12T09:00:00"))
                .isEqualTo("20260612T090000");
    }

    @Test
    void toIcs_timedEvent_dtStartAndDtEndPresent() {
        CalendarEvent ev = new CalendarEvent(
                "ev-1", "Sprint Planning",
                "2026-06-12T09:00:00", "2026-06-12T11:00:00",
                false, null, List.of(), null, null, List.of(), null,
                new LinkedHashMap<>());
        String out = svc.toIcs(
                new CalendarDocument("calendar", List.of(ev), new LinkedHashMap<>()),
                null);
        assertThat(out).contains("BEGIN:VEVENT");
        assertThat(out).contains("UID:ev-1");
        assertThat(out).contains("SUMMARY:Sprint Planning");
        assertThat(out).contains("DTSTART:20260612T090000");
        assertThat(out).contains("DTEND:20260612T110000");
        assertThat(out).contains("DTSTAMP:");
        assertThat(out).contains("END:VEVENT");
    }

    // ── All-day events ─────────────────────────────────────────────

    @Test
    void renderDateOnly_isoDate() {
        assertThat(IcsExportService.renderDateOnly("2026-07-15"))
                .isEqualTo("20260715");
    }

    @Test
    void renderDateOnlyExclusive_addsOneDayToExplicitEnd() {
        assertThat(IcsExportService.renderDateOnlyExclusive("2026-07-28", "2026-07-15"))
                .isEqualTo("20260729");
    }

    @Test
    void renderDateOnlyExclusive_addsOneDayToStartWhenEndMissing() {
        assertThat(IcsExportService.renderDateOnlyExclusive(null, "2026-07-15"))
                .isEqualTo("20260716");
    }

    @Test
    void toIcs_allDayEvent_emitsValueDateProperties() {
        CalendarEvent ev = new CalendarEvent(
                "v1", "Urlaub Frankreich",
                "2026-07-15", "2026-07-28",
                true, null, List.of(), null, null, List.of(), null,
                new LinkedHashMap<>());
        String out = svc.toIcs(
                new CalendarDocument("calendar", List.of(ev), new LinkedHashMap<>()),
                null);
        assertThat(out).contains("DTSTART;VALUE=DATE:20260715");
        // 2026-07-28 + 1 day = 2026-07-29 (exclusive per RFC 5545)
        assertThat(out).contains("DTEND;VALUE=DATE:20260729");
    }

    // ── Recurrence ─────────────────────────────────────────────────

    @Test
    void toIcs_recurringEvent_rruleEmittedWithoutPrefix() {
        CalendarEvent ev = new CalendarEvent(
                "stand", "Standup",
                "2026-06-01T09:00", "2026-06-01T09:15",
                false, null, List.of(),
                "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;UNTIL=20261231T000000Z",
                null, List.of(), null, new LinkedHashMap<>());
        String out = svc.toIcs(
                new CalendarDocument("calendar", List.of(ev), new LinkedHashMap<>()),
                null);
        assertThat(out).contains("RRULE:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;UNTIL=20261231T000000Z");
    }

    @Test
    void toIcs_rruleWithLeadingPrefix_strippedOnExport() {
        CalendarEvent ev = new CalendarEvent(
                "s", "x", "2026-06-01T09:00", null, false, null,
                List.of(), "RRULE:FREQ=DAILY", null, List.of(), null,
                new LinkedHashMap<>());
        String out = svc.toIcs(
                new CalendarDocument("calendar", List.of(ev), new LinkedHashMap<>()),
                null);
        // Must NOT end up with double prefix: "RRULE:RRULE:FREQ=…"
        assertThat(out).contains("RRULE:FREQ=DAILY");
        assertThat(out).doesNotContain("RRULE:RRULE:");
    }

    // ── Text fields + escaping ─────────────────────────────────────

    @Test
    void toIcs_escapesCommaSemicolonBackslashAndNewline() {
        CalendarEvent ev = new CalendarEvent(
                "x", "Title, with comma", "2026-06-12T09:00:00",
                null, false,
                "Room 1; floor 2",
                List.of(),
                null, null, List.of(),
                "Line one\nLine two\\ with backslash",
                new LinkedHashMap<>());
        String out = svc.toIcs(
                new CalendarDocument("calendar", List.of(ev), new LinkedHashMap<>()),
                null);
        assertThat(out).contains("SUMMARY:Title\\, with comma");
        assertThat(out).contains("LOCATION:Room 1\\; floor 2");
        assertThat(out).contains("DESCRIPTION:Line one\\nLine two\\\\ with backslash");
    }

    @Test
    void toIcs_attendeeWithEmailLooks_emitsMailtoLine() {
        CalendarEvent ev = new CalendarEvent(
                "x", "Review", "2026-06-12T14:00:00", null, false,
                null, List.of("alice@example.com", "Bob Smith"),
                null, null, List.of(), null, new LinkedHashMap<>());
        String out = svc.toIcs(
                new CalendarDocument("calendar", List.of(ev), new LinkedHashMap<>()),
                null);
        assertThat(out).contains("ATTENDEE:mailto:alice@example.com");
        assertThat(out).contains("ATTENDEE;CN=Bob Smith");
    }

    @Test
    void toIcs_categories_joinsTagsWithCommas() {
        CalendarEvent ev = new CalendarEvent(
                "x", "Talk", "2026-06-12T14:00:00", null, false,
                null, List.of(), null, null,
                List.of("work", "important", "Q3"),
                null, new LinkedHashMap<>());
        String out = svc.toIcs(
                new CalendarDocument("calendar", List.of(ev), new LinkedHashMap<>()),
                null);
        assertThat(out).contains("CATEGORIES:work,important,Q3");
    }

    // ── Line folding ───────────────────────────────────────────────

    @Test
    void toIcs_longSummary_isFoldedAt75Octets() {
        String longTitle = "A".repeat(120);
        CalendarEvent ev = new CalendarEvent(
                "x", longTitle, "2026-06-12T09:00:00", null, false,
                null, List.of(), null, null, List.of(), null,
                new LinkedHashMap<>());
        String out = svc.toIcs(
                new CalendarDocument("calendar", List.of(ev), new LinkedHashMap<>()),
                null);
        // Folded line: original 75 chars, CRLF, single SPACE, rest.
        // Smoke test — no individual line exceeds 75 chars + CRLF.
        for (String line : out.split("\r\n")) {
            assertThat(line.length()).isLessThanOrEqualTo(75);
        }
    }

    // ── Round-trip via IcsToCalendarTool parser ────────────────────

    @Test
    void roundTrip_throughParser_recoversCoreFields() {
        CalendarEvent ev = new CalendarEvent(
                "evt-1", "Sprint Planning",
                "2026-06-12T09:00:00", "2026-06-12T11:00:00",
                false, "Office",
                List.of("alice@example.com"),
                "FREQ=WEEKLY;BYDAY=MO",
                null, List.of("work"),
                "Discuss the plan.", new LinkedHashMap<>());
        String ics = svc.toIcs(
                new CalendarDocument("calendar", List.of(ev), new LinkedHashMap<>()),
                null);
        List<CalendarEvent> parsed = IcsToCalendarTool.parseIcs(ics);

        assertThat(parsed).hasSize(1);
        CalendarEvent r = parsed.get(0);
        assertThat(r.id()).isEqualTo("evt-1");
        assertThat(r.title()).isEqualTo("Sprint Planning");
        assertThat(r.location()).isEqualTo("Office");
        assertThat(r.notes()).isEqualTo("Discuss the plan.");
        assertThat(r.recurrence()).isEqualTo("FREQ=WEEKLY;BYDAY=MO");
        assertThat(r.tags()).containsExactly("work");
        assertThat(r.attendees()).containsExactly("alice@example.com");
    }

    @Test
    void roundTrip_allDay_isRecoveredAsAllDay() {
        CalendarEvent ev = new CalendarEvent(
                "v", "Urlaub", "2026-07-15", "2026-07-28",
                true, null, List.of(), null, null,
                List.of("private"), null, new LinkedHashMap<>());
        String ics = svc.toIcs(
                new CalendarDocument("calendar", List.of(ev), new LinkedHashMap<>()),
                null);
        List<CalendarEvent> parsed = IcsToCalendarTool.parseIcs(ics);

        assertThat(parsed.get(0).allDay()).isTrue();
        assertThat(parsed.get(0).start()).isEqualTo("2026-07-15");
        // We added one day on export (exclusive) — parser sees that
        // raw value verbatim. Real calendar apps reverse the +1 day
        // when they render; we don't compensate on the way back in,
        // so the round-trip end is the exclusive one. Document this
        // surprise so future readers don't think it's a bug.
        assertThat(parsed.get(0).end()).isEqualTo("2026-07-29");
    }
}
