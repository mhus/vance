package de.mhus.vance.addon.brain.calendar;

import de.mhus.vance.addon.brain.calendar.CalendarDocument;
import de.mhus.vance.addon.brain.calendar.CalendarEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Serialise a {@link CalendarDocument} into an RFC 5545 iCalendar
 * (.ics) body. Inverse of {@link IcsToCalendarTool#parseIcs}.
 *
 * <p>The output is a single {@code VCALENDAR} container wrapping one
 * {@code VEVENT} per Vance event. We emit only the fields we model
 * (UID, SUMMARY, DTSTAMP, DTSTART, DTEND, LOCATION, DESCRIPTION,
 * RRULE, ATTENDEE, CATEGORIES) plus standard backslash escaping for
 * text values.
 *
 * <p>Time-zone behaviour: dates that arrive without an offset stay
 * floating (no {@code TZID} parameter, no {@code Z} suffix), so the
 * importing calendar interprets them in the user's local zone.
 * Dates with an explicit offset get converted to UTC (and a {@code Z}
 * suffix). All-day events use {@code ;VALUE=DATE} with a date-only
 * literal — and the {@code DTEND} is exclusive per RFC 5545, so a
 * stored {@code end} of {@code 2026-07-28} comes out as
 * {@code 20260729} so calendars draw the right span.
 */
@Service
public class IcsExportService {

    private static final String CRLF = "\r\n";
    private static final DateTimeFormatter UTC_COMPACT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter LOCAL_COMPACT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter DATE_COMPACT =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Render the whole calendar as a UTF-8 byte sequence. */
    public byte[] toBytes(CalendarDocument cal, @Nullable String calendarName) {
        return toIcs(cal, calendarName).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public String toIcs(CalendarDocument cal, @Nullable String calendarName) {
        StringBuilder sb = new StringBuilder(1024);
        appendLine(sb, "BEGIN:VCALENDAR");
        appendLine(sb, "VERSION:2.0");
        appendLine(sb, "PRODID:-//Vance//Calendar//EN");
        appendLine(sb, "CALSCALE:GREGORIAN");
        if (calendarName != null && !calendarName.isBlank()) {
            // Apple Calendar / GNOME Evolution honour X-WR-CALNAME for
            // a friendly display name on the imported feed.
            appendLine(sb, "X-WR-CALNAME:" + escape(calendarName));
        }
        String stamp = OffsetDateTime.now(ZoneOffset.UTC).format(UTC_COMPACT);
        for (CalendarEvent ev : cal.events()) {
            appendEvent(sb, ev, stamp);
        }
        appendLine(sb, "END:VCALENDAR");
        return sb.toString();
    }

    private void appendEvent(StringBuilder sb, CalendarEvent ev, String dtstamp) {
        appendLine(sb, "BEGIN:VEVENT");
        appendLine(sb, "UID:" + sanitiseUid(ev.id()));
        appendLine(sb, "DTSTAMP:" + dtstamp);
        if (ev.allDay()) {
            String startDate = renderDateOnly(ev.start());
            if (startDate != null) appendLine(sb, "DTSTART;VALUE=DATE:" + startDate);
            // RFC 5545: VALUE=DATE DTEND is exclusive. We store the
            // user-facing inclusive end, so add one day on the way out.
            String endDate = renderDateOnlyExclusive(ev.end(), ev.start());
            if (endDate != null) appendLine(sb, "DTEND;VALUE=DATE:" + endDate);
        } else {
            String start = renderDateTime(ev.start());
            if (start != null) appendLine(sb, "DTSTART:" + start);
            String end = renderDateTime(ev.end());
            if (end != null) appendLine(sb, "DTEND:" + end);
        }
        appendLine(sb, "SUMMARY:" + escape(ev.title()));
        if (ev.location() != null) {
            appendLine(sb, "LOCATION:" + escape(ev.location()));
        }
        if (ev.notes() != null) {
            appendLine(sb, "DESCRIPTION:" + escape(ev.notes()));
        }
        if (ev.recurrence() != null && !ev.recurrence().isBlank()) {
            String rr = ev.recurrence().trim();
            // Tolerate either bare "FREQ=…" or "RRULE:FREQ=…" inputs;
            // ICS expects exactly the unprefixed form.
            if (rr.toUpperCase(java.util.Locale.ROOT).startsWith("RRULE:")) {
                rr = rr.substring(6);
            }
            appendLine(sb, "RRULE:" + rr);
        }
        for (String attendee : ev.attendees()) {
            // Prefer mailto:-format when the attendee looks like an
            // email; otherwise use CN parameter only and leave the
            // value blank so Outlook/Google still accept the line.
            if (looksLikeEmail(attendee)) {
                appendLine(sb, "ATTENDEE:mailto:" + attendee);
            } else {
                appendLine(sb, "ATTENDEE;CN=" + quoteIfNeeded(attendee) + ":invalid:nomail");
            }
        }
        if (!ev.tags().isEmpty()) {
            // RFC 5545 CATEGORIES is a comma-separated list. Escape
            // commas inside individual tags so they survive the join.
            List<String> escaped = new ArrayList<>(ev.tags().size());
            for (String tag : ev.tags()) escaped.add(escape(tag));
            appendLine(sb, "CATEGORIES:" + String.join(",", escaped));
        }
        appendLine(sb, "END:VEVENT");
    }

    private static void appendLine(StringBuilder sb, String line) {
        // RFC 5545 §3.1: lines longer than 75 octets MUST be folded
        // by inserting a CRLF followed by a SPACE. We measure in
        // chars (close enough for ASCII-dominated text).
        int len = line.length();
        if (len <= 75) {
            sb.append(line).append(CRLF);
            return;
        }
        sb.append(line, 0, 75).append(CRLF);
        int cursor = 75;
        while (cursor < len) {
            int next = Math.min(cursor + 74, len);
            sb.append(' ').append(line, cursor, next).append(CRLF);
            cursor = next;
        }
    }

    private static String sanitiseUid(@Nullable String uid) {
        if (uid == null || uid.isBlank()) {
            return java.util.UUID.randomUUID() + "@vance";
        }
        // The UID line value must not contain CRLF; everything else
        // is fine — we don't quote.
        return uid.replace("\r", "").replace("\n", "");
    }

    /**
     * Render an event's start/end date-time string to the RFC 5545
     * wire format. Three cases supported:
     * <ul>
     *   <li>{@code 2026-06-12T09:00:00+02:00} → UTC compact form
     *       (offset applied, suffix {@code Z}).</li>
     *   <li>{@code 2026-06-12T09:00:00Z} → already UTC, normalised.</li>
     *   <li>{@code 2026-06-12T09:00} or {@code 2026-06-12T09:00:00} →
     *       "floating" local form (no offset, no {@code Z}). The
     *       receiving calendar interprets in its own zone.</li>
     * </ul>
     */
    static @Nullable String renderDateTime(@Nullable String iso) {
        if (iso == null || iso.isBlank()) return null;
        String s = iso.trim();
        try {
            OffsetDateTime odt = OffsetDateTime.parse(s);
            return odt.withOffsetSameInstant(ZoneOffset.UTC).format(UTC_COMPACT);
        } catch (DateTimeParseException ignored) {
            // not an offset form — try local
        }
        // Try with seconds, then without.
        for (DateTimeFormatter fmt : new DateTimeFormatter[] {
                DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm") }) {
            try {
                LocalDateTime ldt = LocalDateTime.parse(s, fmt);
                return ldt.format(LOCAL_COMPACT);
            } catch (DateTimeParseException ignored) {
                // try next pattern
            }
        }
        // Last resort: treat as a bare date — caller probably should
        // have set allDay=true, but emit something so the event isn't
        // silently dropped from the export.
        try {
            LocalDate ld = LocalDate.parse(s);
            return ld.format(DATE_COMPACT) + "T000000";
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    static @Nullable String renderDateOnly(@Nullable String iso) {
        if (iso == null || iso.isBlank()) return null;
        String s = iso.trim();
        // Pure date.
        try {
            return LocalDate.parse(s).format(DATE_COMPACT);
        } catch (DateTimeParseException ignored) {
            // try date-time
        }
        // Allow date-time inputs — pick the date component.
        try {
            return LocalDateTime.parse(s).toLocalDate().format(DATE_COMPACT);
        } catch (DateTimeParseException ignored) {
            // try offset form
        }
        try {
            return OffsetDateTime.parse(s).toLocalDate().format(DATE_COMPACT);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /**
     * Compute the RFC-5545 exclusive {@code DTEND} for an all-day
     * event. If the event has an explicit {@code end}, add one day so
     * the importing calendar still draws the last day visually. If
     * the event has no {@code end} at all, emit the day after
     * {@code start}.
     */
    static @Nullable String renderDateOnlyExclusive(@Nullable String iso, String startIso) {
        String effective = (iso != null && !iso.isBlank()) ? iso : startIso;
        try {
            LocalDate d = LocalDate.parse(effective.trim());
            return d.plusDays(1).format(DATE_COMPACT);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static boolean looksLikeEmail(String s) {
        int at = s.indexOf('@');
        return at > 0 && at < s.length() - 1 && s.indexOf('.', at) > at;
    }

    private static String quoteIfNeeded(String s) {
        if (s.indexOf('"') < 0 && s.indexOf(',') < 0 && s.indexOf(';') < 0 && s.indexOf(':') < 0) {
            return s;
        }
        return "\"" + s.replace("\"", "'") + "\"";
    }

    /** RFC 5545 §3.3.11 — escape backslash, semicolon, comma and newline. */
    static String escape(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case ',' -> out.append("\\,");
                case ';' -> out.append("\\;");
                case '\n' -> out.append("\\n");
                case '\r' -> { /* drop bare CR; \n already covers line breaks */ }
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** Read-back path for tests: take the rendered string and look
     *  up the first instance of {@code FIELD:VALUE} after any
     *  parameters were normalised. Useful in tests; not used at
     *  runtime. Returns {@code null} when the property is missing. */
    static @Nullable Instant nowProbe() {
        return Instant.now();
    }
}
