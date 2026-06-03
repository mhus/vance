package de.mhus.vance.addon.brain.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.addon.brain.calendar.CalendarEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the iCalendar parser embedded in
 * {@link IcsToCalendarTool}. Focuses on the parser proper (no Spring
 * context, no DocumentService) — verifies line unfolding, escape
 * sequences, dt parsing, allDay detection, attendee CN extraction
 * and the soft tolerance for missing fields.
 */
class IcsToCalendarToolTest {

    // ── happy path ─────────────────────────────────────────────────

    @Test
    void parseIcs_singleEvent_pullsCoreFields() {
        String ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:evt-1@example.com
                SUMMARY:Sprint Planning
                DTSTART:20260612T090000
                DTEND:20260612T110000
                LOCATION:Office
                DESCRIPTION:Discuss the plan.
                END:VEVENT
                END:VCALENDAR
                """;

        List<CalendarEvent> events = IcsToCalendarTool.parseIcs(ics);

        assertThat(events).hasSize(1);
        CalendarEvent ev = events.get(0);
        assertThat(ev.id()).isEqualTo("evt-1@example.com");
        assertThat(ev.title()).isEqualTo("Sprint Planning");
        assertThat(ev.start()).isEqualTo("2026-06-12T09:00:00");
        assertThat(ev.end()).isEqualTo("2026-06-12T11:00:00");
        assertThat(ev.location()).isEqualTo("Office");
        assertThat(ev.notes()).isEqualTo("Discuss the plan.");
        assertThat(ev.allDay()).isFalse();
    }

    @Test
    void parseIcs_utcTimestamp_keepsZSuffix() {
        String ics = """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:u1
                SUMMARY:UTC Event
                DTSTART:20260612T090000Z
                DTEND:20260612T100000Z
                END:VEVENT
                END:VCALENDAR
                """;

        List<CalendarEvent> events = IcsToCalendarTool.parseIcs(ics);
        assertThat(events.get(0).start()).isEqualTo("2026-06-12T09:00:00Z");
        assertThat(events.get(0).end()).isEqualTo("2026-06-12T10:00:00Z");
    }

    @Test
    void parseIcs_dateOnlyValueParam_marksAllDay() {
        String ics = """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:u1
                SUMMARY:Holiday
                DTSTART;VALUE=DATE:20260715
                DTEND;VALUE=DATE:20260728
                END:VEVENT
                END:VCALENDAR
                """;

        List<CalendarEvent> events = IcsToCalendarTool.parseIcs(ics);
        CalendarEvent ev = events.get(0);
        assertThat(ev.allDay()).isTrue();
        assertThat(ev.start()).isEqualTo("2026-07-15");
        assertThat(ev.end()).isEqualTo("2026-07-28");
    }

    @Test
    void parseIcs_recurrenceRule_passedThroughVerbatim() {
        String ics = """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:u1
                SUMMARY:Standup
                DTSTART:20260601T090000
                RRULE:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR
                END:VEVENT
                END:VCALENDAR
                """;

        List<CalendarEvent> events = IcsToCalendarTool.parseIcs(ics);
        assertThat(events.get(0).recurrence()).isEqualTo("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR");
    }

    @Test
    void parseIcs_attendees_prefersCommonName() {
        String ics = """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:u1
                SUMMARY:Review
                DTSTART:20260612T140000
                ATTENDEE;CN=Alice Smith;ROLE=REQ-PARTICIPANT:mailto:alice@example.com
                ATTENDEE:mailto:bob@example.com
                END:VEVENT
                END:VCALENDAR
                """;

        List<CalendarEvent> events = IcsToCalendarTool.parseIcs(ics);
        assertThat(events.get(0).attendees())
                .containsExactly("Alice Smith", "bob@example.com");
    }

    @Test
    void parseIcs_categories_splitsIntoTags() {
        String ics = """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:u1
                SUMMARY:Talk
                DTSTART:20260612T140000
                CATEGORIES:work,important,Q3
                END:VEVENT
                END:VCALENDAR
                """;

        List<CalendarEvent> events = IcsToCalendarTool.parseIcs(ics);
        assertThat(events.get(0).tags()).containsExactly("work", "important", "Q3");
    }

    @Test
    void parseIcs_lineFolding_isUnfoldedBeforeFieldExtraction() {
        // RFC 5545 requires long lines to be wrapped at column 75
        // with a leading SPACE or HTAB on each continuation line.
        // Outlook/Google both do this for any DESCRIPTION over ~75 chars.
        // RFC 5545 line folding: a continuation line starts with a
        // single SPACE/HTAB which is stripped on unfold — no space
        // is re-inserted at the join. So when the source needs a
        // space to survive the fold it has to *carry* the space at
        // the fold point. Both forms below are valid; we test both.
        String ics = "BEGIN:VCALENDAR\r\n"
                + "BEGIN:VEVENT\r\n"
                + "UID:u1\r\n"
                + "SUMMARY:Test\r\n"
                + "DTSTART:20260612T140000\r\n"
                + "DESCRIPTION:This is a very long descript\r\n"
                + " ion that wraps across two \r\n"
                + " lines per RFC 5545.\r\n"
                + "END:VEVENT\r\n"
                + "END:VCALENDAR\r\n";

        List<CalendarEvent> events = IcsToCalendarTool.parseIcs(ics);
        assertThat(events.get(0).notes())
                .isEqualTo("This is a very long description that wraps across two lines per RFC 5545.");
    }

    @Test
    void parseIcs_escapeSequences_areDecoded() {
        String ics = """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:u1
                SUMMARY:Meeting
                DTSTART:20260612T140000
                DESCRIPTION:Line one\\nLine two\\, with comma\\; and semi\\\\and backslash
                END:VEVENT
                END:VCALENDAR
                """;

        List<CalendarEvent> events = IcsToCalendarTool.parseIcs(ics);
        assertThat(events.get(0).notes())
                .isEqualTo("Line one\nLine two, with comma; and semi\\and backslash");
    }

    @Test
    void parseIcs_multipleEvents_areAllReturned() {
        String ics = """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:a
                SUMMARY:Event A
                DTSTART:20260612T140000
                END:VEVENT
                BEGIN:VEVENT
                UID:b
                SUMMARY:Event B
                DTSTART:20260613T140000
                END:VEVENT
                END:VCALENDAR
                """;

        List<CalendarEvent> events = IcsToCalendarTool.parseIcs(ics);
        assertThat(events).extracting(CalendarEvent::id).containsExactly("a", "b");
    }

    // ── defensive ─────────────────────────────────────────────────

    @Test
    void parseIcs_eventWithoutDtStart_isSilentlyDropped() {
        String ics = """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:no-anchor
                SUMMARY:Nowhere event
                END:VEVENT
                BEGIN:VEVENT
                UID:anchored
                SUMMARY:Real event
                DTSTART:20260612T140000
                END:VEVENT
                END:VCALENDAR
                """;

        List<CalendarEvent> events = IcsToCalendarTool.parseIcs(ics);
        assertThat(events).extracting(CalendarEvent::id).containsExactly("anchored");
    }

    @Test
    void parseIcs_missingUid_isAutoFilledWithUuid() {
        String ics = """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                SUMMARY:Anonymous event
                DTSTART:20260612T140000
                END:VEVENT
                END:VCALENDAR
                """;

        List<CalendarEvent> events = IcsToCalendarTool.parseIcs(ics);
        String id = events.get(0).id();
        assertThat(id).isNotBlank();
        assertThat(id).hasSize(36); // UUID
    }

    @Test
    void parseIcs_missingSummary_fallsBackToUntitled() {
        String ics = """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:u1
                DTSTART:20260612T140000
                END:VEVENT
                END:VCALENDAR
                """;

        List<CalendarEvent> events = IcsToCalendarTool.parseIcs(ics);
        assertThat(events.get(0).title()).isEqualTo("Untitled event");
    }

    @Test
    void parseIcs_emptyInput_yieldsEmptyList() {
        assertThat(IcsToCalendarTool.parseIcs("")).isEmpty();
    }

    @Test
    void parseIcs_noVEvent_yieldsEmptyList() {
        String ics = """
                BEGIN:VCALENDAR
                PRODID:-//Test//Test//EN
                END:VCALENDAR
                """;
        assertThat(IcsToCalendarTool.parseIcs(ics)).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────

    @Test
    void parseIcsDate_dateOnly_returnsIsoDate() {
        assertThat(IcsToCalendarTool.parseIcsDate("20260612", true))
                .isEqualTo("2026-06-12");
    }

    @Test
    void parseIcsDate_dateTimeLocal_returnsIsoLocal() {
        assertThat(IcsToCalendarTool.parseIcsDate("20260612T143000", false))
                .isEqualTo("2026-06-12T14:30:00");
    }

    @Test
    void parseIcsDate_dateTimeUtc_keepsZSuffix() {
        assertThat(IcsToCalendarTool.parseIcsDate("20260612T143000Z", false))
                .isEqualTo("2026-06-12T14:30:00Z");
    }

    @Test
    void slug_normalisesTitle() {
        assertThat(IcsToCalendarTool.slug("Sprint Planning Q3"))
                .isEqualTo("sprint-planning-q3");
        assertThat(IcsToCalendarTool.slug("  --foo--bar--  "))
                .isEqualTo("foo-bar");
    }
}
