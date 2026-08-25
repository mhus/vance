package de.mhus.vance.addon.brain.calendar;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Resolves a stored position string ({@link TimelineEntry#from()} and
 * friends) to a number on which "earlier" is "smaller", for the axis
 * the document declared.
 *
 * <p>This is the whole reason {@code timeline} is a kind of its own:
 * both a geological chart and a minute-resolution reconstruction
 * collapse onto one number line, and only the tick <em>labels</em>
 * differ. A calendar has no such projection — it is bound to the
 * Gregorian calendar, which cannot represent 201.4 million years ago
 * at all.
 *
 * <p>Two shapes reduce to that number line:
 * <ul>
 *   <li>{@code NUMERIC} — the value as written, negated when the axis
 *       counts {@code ago}, so "201.4 Ma" sorts before "143.1 Ma".</li>
 *   <li>{@code DATETIME} — epoch seconds. Values without an offset are
 *       read as UTC. That is a positioning convention, not a claim
 *       about the author's zone: the renderer parses the same strings
 *       in the reader's local zone for display, and a uniform shift
 *       cannot change the order of entries, which is all this class is
 *       used for.</li>
 * </ul>
 *
 * <p>Returns {@code null} rather than throwing for anything it cannot
 * read. An unparseable position means one entry the renderer skips and
 * the validator reports — never a document that fails to open.
 */
public final class TimelineScale {

    /**
     * ISO-8601-ish instant, deliberately more permissive than
     * {@code java.time}: year-only and year-month are accepted because
     * a historical timeline is naturally written in years
     * ({@code from: "1969"}), and a leading minus is accepted for BCE
     * ({@code "-0044-03-15"}).
     */
    private static final Pattern INSTANT = Pattern.compile(
            "^(-?\\d{1,9})"                                  // year
            + "(?:-(\\d{1,2})"                               // month
            + "(?:-(\\d{1,2}))?)?"                           // day
            + "(?:[T ](\\d{1,2}):(\\d{2})"                   // hh:mm
            + "(?::(\\d{2})(?:\\.\\d+)?)?)?"                 // :ss(.fff)
            + "\\s*(Z|z|[+-]\\d{2}:?\\d{2})?$");             // offset

    private TimelineScale() {
        // utility class
    }

    /**
     * Position of {@code raw} on {@code axis}, or {@code null} when the
     * value is absent or unreadable for that axis mode.
     */
    public static @Nullable Double position(TimelineAxis axis, @Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        return switch (axis.mode()) {
            case NUMERIC -> {
                Double v = numeric(raw);
                if (v == null) yield null;
                yield axis.direction() == TimelineAxis.TimelineDirection.AGO ? -v : v;
            }
            case DATETIME -> instantSeconds(raw);
        };
    }

    /** A bare number, tolerating surrounding whitespace and a leading {@code +}. */
    public static @Nullable Double numeric(String raw) {
        String s = raw.trim();
        if (s.startsWith("+")) s = s.substring(1);
        try {
            double d = Double.parseDouble(s);
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Epoch seconds for an ISO-8601-ish instant. Missing components
     * default to the start of their range (month {@code 01}, day
     * {@code 01}, midnight) — a year alone means "the beginning of that
     * year", which is what an author writing {@code from: "1969"}
     * means, and keeps {@code 1969} strictly before {@code 1969-07-20}.
     */
    public static @Nullable Double instantSeconds(String raw) {
        Matcher m = INSTANT.matcher(raw.trim());
        if (!m.matches()) return null;
        try {
            int year = Integer.parseInt(m.group(1));
            int month = m.group(2) != null ? Integer.parseInt(m.group(2)) : 1;
            int day = m.group(3) != null ? Integer.parseInt(m.group(3)) : 1;
            int hour = m.group(4) != null ? Integer.parseInt(m.group(4)) : 0;
            int minute = m.group(5) != null ? Integer.parseInt(m.group(5)) : 0;
            int second = m.group(6) != null ? Integer.parseInt(m.group(6)) : 0;
            ZoneOffset offset = parseOffset(m.group(7));
            LocalDateTime local = LocalDateTime.of(year, month, day, hour, minute, second);
            return (double) local.toEpochSecond(offset);
        } catch (RuntimeException e) {
            // Out-of-range month/day/hour — an unreadable position, not
            // a broken document.
            return null;
        }
    }

    private static ZoneOffset parseOffset(@Nullable String raw) {
        if (raw == null || raw.isBlank() || "Z".equalsIgnoreCase(raw)) return ZoneOffset.UTC;
        String s = raw.length() == 5 ? raw.substring(0, 3) + ":" + raw.substring(3) : raw;
        return ZoneOffset.of(s);
    }
}
