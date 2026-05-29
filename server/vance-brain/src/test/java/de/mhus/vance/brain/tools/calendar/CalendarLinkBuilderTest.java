package de.mhus.vance.brain.tools.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.kind.CalendarEvent;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Sanity checks for the deep-link URL builders that {@code
 * calendar_create} ships back per event. We assert on URL fragments
 * rather than the full strings — parameter ordering varies across JDK
 * versions on the {@link java.net.URLEncoder} side.
 */
class CalendarLinkBuilderTest {

    private static CalendarEvent timed(String title, String start, String end) {
        return new CalendarEvent(
                "id", title, start, end, false,
                null, List.of(), null, null, List.of(), null,
                new LinkedHashMap<>());
    }

    // ── Google date range ─────────────────────────────────────────

    @Test
    void googleDateRange_localTimeBecomesUtcCompact() {
        // Local time treated as UTC for the link (Z suffix): the user
        // sees exactly what they typed in their Google calendar, no
        // surprise timezone shift.
        assertThat(CalendarLinkBuilder.googleDateRange(
                timed("X", "2026-06-12T09:00", "2026-06-12T11:00")))
                .isEqualTo("20260612T090000Z/20260612T110000Z");
    }

    @Test
    void googleDateRange_offsetTimeNormalisedToUtc() {
        assertThat(CalendarLinkBuilder.googleDateRange(
                timed("X", "2026-06-12T09:00:00+02:00", "2026-06-12T11:00:00+02:00")))
                .isEqualTo("20260612T070000Z/20260612T090000Z");
    }

    @Test
    void googleDateRange_endMissingGetsDefault30Minutes() {
        assertThat(CalendarLinkBuilder.googleDateRange(
                timed("X", "2026-06-12T09:00", null)))
                .isEqualTo("20260612T090000Z/20260612T093000Z");
    }

    @Test
    void googleDateRange_allDayIsExclusivePlusOne() {
        CalendarEvent ev = new CalendarEvent(
                "id", "Holiday", "2026-07-15", "2026-07-28", true,
                null, List.of(), null, null, List.of(), null,
                new LinkedHashMap<>());
        assertThat(CalendarLinkBuilder.googleDateRange(ev))
                .isEqualTo("20260715/20260729");
    }

    @Test
    void googleUrl_includesTitleAndLocationEncoded() {
        CalendarEvent ev = new CalendarEvent(
                "id", "Sprint Planning",
                "2026-06-12T09:00", "2026-06-12T11:00", false,
                "Conf Room A & B", List.of(), null, null, List.of(),
                "Plan & retro", new LinkedHashMap<>());
        String url = CalendarLinkBuilder.googleUrl(ev);
        assertThat(url).startsWith("https://calendar.google.com/calendar/render");
        assertThat(url).contains("action=TEMPLATE");
        assertThat(url).contains("text=Sprint+Planning");
        assertThat(url).contains("dates=20260612T090000Z%2F20260612T110000Z");
        assertThat(url).contains("location=Conf+Room+A+%26+B");
        assertThat(url).contains("details=Plan+%26+retro");
    }

    // ── Outlook ───────────────────────────────────────────────────

    @Test
    void outlookStart_localTimePassesThroughAsIso() {
        assertThat(CalendarLinkBuilder.outlookStart(
                timed("X", "2026-06-12T09:00", null)))
                .isEqualTo("2026-06-12T09:00:00");
    }

    @Test
    void outlookStart_offsetFormStripsTimezone() {
        // Outlook deep-link wants local form — we deliberately drop
        // the offset so the user's profile timezone applies.
        assertThat(CalendarLinkBuilder.outlookStart(
                timed("X", "2026-06-12T09:00:00+02:00", null)))
                .isEqualTo("2026-06-12T09:00:00");
    }

    @Test
    void outlookEnd_missingDefaults30Minutes() {
        assertThat(CalendarLinkBuilder.outlookEnd(
                timed("X", "2026-06-12T09:00", null)))
                .isEqualTo("2026-06-12T09:30:00");
    }

    @Test
    void outlookStart_allDayUsesIsoDate() {
        CalendarEvent ev = new CalendarEvent(
                "id", "Vacation", "2026-07-15", "2026-07-28", true,
                null, List.of(), null, null, List.of(), null,
                new LinkedHashMap<>());
        assertThat(CalendarLinkBuilder.outlookStart(ev)).isEqualTo("2026-07-15");
        assertThat(CalendarLinkBuilder.outlookEnd(ev)).isEqualTo("2026-07-28");
    }

    @Test
    void outlookUrl_carriesAllRelevantParams() {
        CalendarEvent ev = new CalendarEvent(
                "id", "Review", "2026-06-12T14:00", "2026-06-12T16:00",
                false, "Zoom", List.of(), null, null, List.of(),
                "End-of-sprint", new LinkedHashMap<>());
        String url = CalendarLinkBuilder.outlookUrl(ev);
        assertThat(url).startsWith("https://outlook.live.com/calendar/0/deeplink/compose");
        assertThat(url).contains("rru=addevent");
        assertThat(url).contains("subject=Review");
        assertThat(url).contains("startdt=2026-06-12T14%3A00%3A00");
        assertThat(url).contains("enddt=2026-06-12T16%3A00%3A00");
        assertThat(url).contains("location=Zoom");
        assertThat(url).contains("body=End-of-sprint");
    }

    @Test
    void outlookUrl_allDaySetsAlldayFlag() {
        CalendarEvent ev = new CalendarEvent(
                "id", "Vacation", "2026-07-15", "2026-07-28", true,
                null, List.of(), null, null, List.of(), null,
                new LinkedHashMap<>());
        String url = CalendarLinkBuilder.outlookUrl(ev);
        assertThat(url).contains("allday=true");
    }

    // ── Combined ──────────────────────────────────────────────────

    @Test
    void buildLinks_yieldsBothProvidersForTimedEvent() {
        CalendarEvent ev = timed("X", "2026-06-12T09:00", "2026-06-12T11:00");
        CalendarLinkBuilder.Links links = CalendarLinkBuilder.buildLinks(ev);
        assertThat(links.google()).isNotNull();
        assertThat(links.outlook()).isNotNull();
    }

    @Test
    void buildLinks_yieldsNullForUnparseableStart() {
        CalendarEvent ev = new CalendarEvent(
                "id", "Bad", "not-a-date", null, false,
                null, List.of(), null, null, List.of(), null,
                new LinkedHashMap<>());
        CalendarLinkBuilder.Links links = CalendarLinkBuilder.buildLinks(ev);
        assertThat(links.google()).isNull();
        assertThat(links.outlook()).isNull();
    }
}
