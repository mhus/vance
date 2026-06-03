package de.mhus.vance.addon.brain.calendar;

import de.mhus.vance.addon.brain.calendar.CalendarEvent;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.jspecify.annotations.Nullable;

/**
 * Build "add to my calendar" deep-links for the major web-calendar
 * apps. The result of {@code calendar_create} (and friends) ships
 * these alongside the saved document so the LLM can paste them into
 * chat — one click and the user has the event in their real
 * Google / Outlook calendar, no OAuth and no backend integration on
 * Vance's side.
 *
 * <p>The TypeScript counterpart lives in {@code CalendarView.vue} so
 * the agenda buttons share the same URL shape; both sides agree on
 * Google's UTC-compact format and Outlook's local-ISO format.
 */
public final class CalendarLinkBuilder {

    private static final DateTimeFormatter UTC_COMPACT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter LOCAL_COMPACT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter DATE_COMPACT =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    private CalendarLinkBuilder() {
        // utility class
    }

    /** Convenience holder for an event's three render flavours. */
    public record Links(@Nullable String google, @Nullable String outlook) { }

    public static Links buildLinks(CalendarEvent ev) {
        return new Links(googleUrl(ev), outlookUrl(ev));
    }

    /**
     * Google Calendar render URL.
     * Format reference:
     *   https://calendar.google.com/calendar/render?action=TEMPLATE&text=…&dates=…&details=…&location=…
     * For all-day events the date range is {@code YYYYMMDD/YYYYMMDD}
     * with the end exclusive (so a stored {@code end=2026-07-28}
     * becomes {@code 20260729}). Timed events use UTC-compact
     * (Google always interprets in UTC when the suffix is {@code Z}).
     */
    public static @Nullable String googleUrl(CalendarEvent ev) {
        String range = googleDateRange(ev);
        if (range == null) return null;
        StringBuilder sb = new StringBuilder(
                "https://calendar.google.com/calendar/render?action=TEMPLATE");
        appendParam(sb, "text", ev.title());
        appendParam(sb, "dates", range);
        if (ev.notes() != null) appendParam(sb, "details", ev.notes());
        if (ev.location() != null) appendParam(sb, "location", ev.location());
        return sb.toString();
    }

    /**
     * Outlook Live deeplink. Times go as ISO-8601 local strings —
     * Outlook Web parses them in the user's profile timezone.
     */
    public static @Nullable String outlookUrl(CalendarEvent ev) {
        String start = outlookStart(ev);
        String end = outlookEnd(ev);
        if (start == null) return null;
        StringBuilder sb = new StringBuilder(
                "https://outlook.live.com/calendar/0/deeplink/compose?path=%2Fcalendar%2Faction%2Fcompose&rru=addevent");
        appendParam(sb, "subject", ev.title());
        appendParam(sb, "startdt", start);
        if (end != null) appendParam(sb, "enddt", end);
        if (ev.allDay()) appendParam(sb, "allday", "true");
        if (ev.notes() != null) appendParam(sb, "body", ev.notes());
        if (ev.location() != null) appendParam(sb, "location", ev.location());
        return sb.toString();
    }

    // ── Google date range ─────────────────────────────────────────

    static @Nullable String googleDateRange(CalendarEvent ev) {
        if (ev.allDay()) {
            LocalDate start = parseDate(ev.start());
            if (start == null) return null;
            LocalDate end = parseDate(ev.end());
            LocalDate endExcl = (end != null ? end : start).plusDays(1);
            return start.format(DATE_COMPACT) + "/" + endExcl.format(DATE_COMPACT);
        }
        String startUtc = toUtcCompact(ev.start());
        if (startUtc == null) return null;
        String endUtc = toUtcCompact(ev.end());
        if (endUtc == null) {
            // Default 30-minute duration when the event has no end —
            // Google still requires a /-separated range.
            OffsetDateTime parsed = parseOffsetOrLocalAsUtc(ev.start());
            if (parsed == null) return null;
            endUtc = parsed.plusMinutes(30).format(UTC_COMPACT);
        }
        return startUtc + "/" + endUtc;
    }

    // ── Outlook ────────────────────────────────────────────────────

    static @Nullable String outlookStart(CalendarEvent ev) {
        if (ev.allDay()) {
            LocalDate d = parseDate(ev.start());
            return d == null ? null : d.toString();
        }
        return toLocalIso(ev.start());
    }

    static @Nullable String outlookEnd(CalendarEvent ev) {
        if (ev.allDay()) {
            LocalDate d = parseDate(ev.end() != null ? ev.end() : ev.start());
            return d == null ? null : d.toString();
        }
        String end = toLocalIso(ev.end());
        if (end != null) return end;
        // Default 30-minute duration so Outlook doesn't reject the
        // request for a missing end.
        LocalDateTime ldt = parseLocalDateTime(ev.start());
        if (ldt != null) return ldt.plusMinutes(30).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        return null;
    }

    // ── Date / time coercion ──────────────────────────────────────

    static @Nullable LocalDate parseDate(@Nullable String iso) {
        if (iso == null || iso.isBlank()) return null;
        String s = iso.trim();
        try { return LocalDate.parse(s); } catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(s).toLocalDate(); } catch (DateTimeParseException ignored) { }
        try { return OffsetDateTime.parse(s).toLocalDate(); } catch (DateTimeParseException ignored) { }
        return null;
    }

    static @Nullable String toUtcCompact(@Nullable String iso) {
        OffsetDateTime odt = parseOffsetOrLocalAsUtc(iso);
        return odt == null ? null : odt.format(UTC_COMPACT);
    }

    /**
     * Parse a Vance start/end string and return it as a UTC
     * {@link OffsetDateTime}. Local strings without offset are
     * treated as UTC for the link — Google interprets the {@code Z}
     * suffix literally so the user sees the time they typed, just
     * without timezone-shift surprises. (Vance doesn't track per-
     * event TZIDs in v1; floating-time fidelity would need v2.)
     */
    static @Nullable OffsetDateTime parseOffsetOrLocalAsUtc(@Nullable String iso) {
        if (iso == null || iso.isBlank()) return null;
        String s = iso.trim();
        try {
            return OffsetDateTime.parse(s).withOffsetSameInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // fall through to local
        }
        LocalDateTime ldt = parseLocalDateTime(s);
        if (ldt != null) return ldt.atOffset(ZoneOffset.UTC);
        return null;
    }

    static @Nullable LocalDateTime parseLocalDateTime(@Nullable String iso) {
        if (iso == null || iso.isBlank()) return null;
        String s = iso.trim();
        for (DateTimeFormatter fmt : new DateTimeFormatter[] {
                DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm") }) {
            try { return LocalDateTime.parse(s, fmt); }
            catch (DateTimeParseException ignored) { /* try next */ }
        }
        return null;
    }

    /** Render as Outlook's local-ISO form, padding seconds if missing. */
    static @Nullable String toLocalIso(@Nullable String iso) {
        LocalDateTime ldt = parseLocalDateTime(iso);
        if (ldt != null) return ldt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        // Try offset form — keep the local component, drop the offset.
        try {
            OffsetDateTime odt = OffsetDateTime.parse(iso == null ? "" : iso.trim());
            return odt.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    // ── URL encoding ──────────────────────────────────────────────

    private static void appendParam(StringBuilder sb, String key, String value) {
        if (value == null) return;
        sb.append('&').append(key).append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }
}
