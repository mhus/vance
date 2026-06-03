package de.mhus.vance.addon.brain.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * RFC 5545 RRULE expansion — same subset as the TypeScript renderer
 * in {@code CalendarView.vue}: {@code FREQ=DAILY|WEEKLY|MONTHLY|YEARLY},
 * {@code INTERVAL}, {@code BYDAY} (WEEKLY only), {@code UNTIL},
 * {@code COUNT}. Exotic tokens (BYMONTHDAY, BYSETPOS, prefixed BYDAY)
 * are ignored; the resulting series captures the common cases without
 * dragging in a full RFC implementation.
 *
 * <p>Used by {@code calendar_aggregate} to materialise occurrences
 * inside a query window. {@code gantt_from_calendars} skips expansion
 * by default (recurring events would blow the Gantt up).
 */
public final class RecurrenceExpander {

    private static final DateTimeFormatter UNTIL_DATE_ONLY =
            DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter UNTIL_UTC =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private static final int MAX_ITERATIONS = 2_000;
    private static final int MAX_OUTPUT = 500;

    private RecurrenceExpander() {
        // utility class
    }

    /** A single materialised occurrence in the expansion window. */
    public record Occurrence(
            CalendarEvent event,
            LocalDateTime start,
            @Nullable LocalDateTime end,
            boolean allDay) {

        public String startIso() {
            return allDay ? start.toLocalDate().toString() : start.toString();
        }

        public @Nullable String endIso() {
            if (end == null) return null;
            return allDay ? end.toLocalDate().toString() : end.toString();
        }
    }

    /**
     * Expand a calendar event into all occurrences whose {@code start}
     * falls in {@code [rangeStart, rangeEnd]}. Non-recurring events
     * yield 0 or 1 occurrence. The hard caps
     * ({@value #MAX_ITERATIONS} iterations, {@value #MAX_OUTPUT}
     * outputs) guard against malformed rules.
     */
    public static List<Occurrence> expand(CalendarEvent ev,
                                          LocalDateTime rangeStart,
                                          LocalDateTime rangeEnd) {
        ParsedRange anchor = parseEventRange(ev);
        if (anchor == null) return List.of();

        List<Occurrence> out = new ArrayList<>();
        if (ev.recurrence() == null || ev.recurrence().isBlank()) {
            if (!anchor.start().isBefore(rangeStart) && !anchor.start().isAfter(rangeEnd)) {
                out.add(new Occurrence(ev, anchor.start(), anchor.end(), anchor.allDay()));
            }
            return out;
        }

        RruleSpec spec = parseRrule(ev.recurrence());
        if (spec == null) {
            if (!anchor.start().isBefore(rangeStart) && !anchor.start().isAfter(rangeEnd)) {
                out.add(new Occurrence(ev, anchor.start(), anchor.end(), anchor.allDay()));
            }
            return out;
        }

        long durationSeconds = anchor.end() == null ? 0
                : java.time.Duration.between(anchor.start(), anchor.end()).getSeconds();

        LocalDateTime cursor = anchor.start();
        int counter = 0;
        int emitted = 0;

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            if (spec.until() != null && cursor.isAfter(spec.until())) break;
            if (spec.count() != null && counter >= spec.count()) break;
            if (cursor.isAfter(rangeEnd)) break;

            List<LocalDateTime> stepOccurrences;
            if (spec.freq() == Freq.WEEKLY && !spec.byday().isEmpty()) {
                stepOccurrences = new ArrayList<>();
                LocalDateTime weekStart = cursor.minusDays(cursor.getDayOfWeek().getValue() % 7);
                for (DayOfWeek dow : spec.byday()) {
                    int offset = dow.getValue() % 7; // Sunday=0
                    LocalDateTime candidate = weekStart.plusDays(offset);
                    // Preserve the time-of-day from the cursor
                    candidate = candidate
                            .withHour(cursor.getHour())
                            .withMinute(cursor.getMinute())
                            .withSecond(cursor.getSecond());
                    if (!candidate.isBefore(anchor.start())) {
                        stepOccurrences.add(candidate);
                    }
                }
            } else {
                stepOccurrences = List.of(cursor);
            }

            for (LocalDateTime occ : stepOccurrences) {
                if (spec.until() != null && occ.isAfter(spec.until())) continue;
                if (spec.count() != null && counter >= spec.count()) break;
                if (!occ.isBefore(rangeStart) && !occ.isAfter(rangeEnd)) {
                    LocalDateTime occEnd = anchor.end() == null
                            ? null : occ.plusSeconds(durationSeconds);
                    out.add(new Occurrence(ev, occ, occEnd, anchor.allDay()));
                    if (++emitted >= MAX_OUTPUT) return out;
                }
                counter++;
            }

            cursor = switch (spec.freq()) {
                case DAILY -> cursor.plusDays(spec.interval());
                case WEEKLY -> cursor.plusDays(7L * spec.interval());
                case MONTHLY -> cursor.plusMonths(spec.interval());
                case YEARLY -> cursor.plusYears(spec.interval());
            };
        }
        return out;
    }

    // ── RRULE parsing ─────────────────────────────────────────────

    enum Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

    record RruleSpec(
            Freq freq,
            int interval,
            List<DayOfWeek> byday,
            @Nullable LocalDateTime until,
            @Nullable Integer count) { }

    static @Nullable RruleSpec parseRrule(String rrule) {
        String body = rrule.replaceFirst("(?i)^RRULE:", "").trim();
        if (body.isEmpty()) return null;
        Freq freq = null;
        int interval = 1;
        List<DayOfWeek> byday = new ArrayList<>();
        LocalDateTime until = null;
        Integer count = null;
        for (String part : body.split(";")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) continue;
            String key = kv[0].trim().toUpperCase(Locale.ROOT);
            String val = kv[1].trim();
            switch (key) {
                case "FREQ" -> {
                    try { freq = Freq.valueOf(val.toUpperCase(Locale.ROOT)); }
                    catch (IllegalArgumentException ignored) { /* skip */ }
                }
                case "INTERVAL" -> {
                    try {
                        int n = Integer.parseInt(val);
                        if (n > 0) interval = n;
                    } catch (NumberFormatException ignored) { }
                }
                case "BYDAY" -> {
                    for (String token : val.split(",")) {
                        String day = token.trim().replaceAll("^[+-]?\\d+", "");
                        DayOfWeek dow = switch (day.toUpperCase(Locale.ROOT)) {
                            case "MO" -> DayOfWeek.MONDAY;
                            case "TU" -> DayOfWeek.TUESDAY;
                            case "WE" -> DayOfWeek.WEDNESDAY;
                            case "TH" -> DayOfWeek.THURSDAY;
                            case "FR" -> DayOfWeek.FRIDAY;
                            case "SA" -> DayOfWeek.SATURDAY;
                            case "SU" -> DayOfWeek.SUNDAY;
                            default -> null;
                        };
                        if (dow != null) byday.add(dow);
                    }
                }
                case "UNTIL" -> until = parseUntil(val);
                case "COUNT" -> {
                    try {
                        int n = Integer.parseInt(val);
                        if (n > 0) count = n;
                    } catch (NumberFormatException ignored) { }
                }
                default -> { /* ignored — exotic token */ }
            }
        }
        return freq == null ? null
                : new RruleSpec(freq, interval, byday, until, count);
    }

    private static @Nullable LocalDateTime parseUntil(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return OffsetDateTime.parse(v.contains("T") ? v.replace("Z", "+00:00") : v)
                    .toLocalDateTime();
        } catch (DateTimeParseException ignored) { /* try compact */ }
        try {
            if (v.length() == 8) {
                return LocalDate.parse(v, UNTIL_DATE_ONLY).atTime(23, 59, 59);
            }
            return LocalDateTime.parse(v, UNTIL_UTC);
        } catch (DateTimeParseException ignored) { }
        return null;
    }

    // ── Event-anchor parsing ──────────────────────────────────────

    record ParsedRange(LocalDateTime start, @Nullable LocalDateTime end, boolean allDay) { }

    static @Nullable ParsedRange parseEventRange(CalendarEvent ev) {
        if (ev.allDay()) {
            LocalDate startDate = parseDate(ev.start());
            if (startDate == null) return null;
            LocalDate endDate = ev.end() != null ? parseDate(ev.end()) : null;
            // All-day events occupy a date — we treat the start as
            // local midnight for window comparisons.
            LocalDateTime start = startDate.atStartOfDay();
            LocalDateTime end = endDate == null ? null : endDate.atTime(23, 59, 59);
            return new ParsedRange(start, end, true);
        }
        LocalDateTime start = parseDateTime(ev.start());
        if (start == null) return null;
        LocalDateTime end = ev.end() != null ? parseDateTime(ev.end()) : null;
        return new ParsedRange(start, end, false);
    }

    static @Nullable LocalDate parseDate(@Nullable String iso) {
        if (iso == null || iso.isBlank()) return null;
        String s = iso.trim();
        try { return LocalDate.parse(s); } catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(s).toLocalDate(); } catch (DateTimeParseException ignored) { }
        try { return OffsetDateTime.parse(s).toLocalDate(); } catch (DateTimeParseException ignored) { }
        return null;
    }

    static @Nullable LocalDateTime parseDateTime(@Nullable String iso) {
        if (iso == null || iso.isBlank()) return null;
        String s = iso.trim();
        try {
            return OffsetDateTime.parse(s).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException ignored) { /* try local */ }
        for (DateTimeFormatter fmt : new DateTimeFormatter[] {
                DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm") }) {
            try { return LocalDateTime.parse(s, fmt); }
            catch (DateTimeParseException ignored) { /* try next */ }
        }
        try {
            return LocalDate.parse(s).atStartOfDay();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    // ── Public deduplication helper ───────────────────────────────

    /** Drop duplicate occurrences (same event-id, same start) — happens
     *  when two passes through the expander overlap for whatever reason. */
    public static List<Occurrence> dedupe(List<Occurrence> occurrences) {
        Set<String> seen = new HashSet<>();
        List<Occurrence> out = new ArrayList<>(occurrences.size());
        for (Occurrence occ : occurrences) {
            String key = occ.event().id() + "@" + occ.start().toString();
            if (seen.add(key)) out.add(occ);
        }
        return out;
    }
}
