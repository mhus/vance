package de.mhus.vance.addon.brain.calendar;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The time axis of a {@code kind: timeline} document — the one thing
 * that makes a timeline something other than a calendar.
 *
 * <p>A calendar's axis is implicit and always the Gregorian calendar,
 * which is why it cannot render the Jurassic (201.4 million years
 * ago is not an ISO-8601 instant) nor a crime reconstruction at
 * minute resolution across parallel actors. A timeline declares its
 * axis instead: either a <b>number line</b> with a free-form unit, or
 * <b>absolute date-times</b>. Both render through the same code —
 * only the tick formatter differs.
 *
 * <p><b>One axis per document.</b> A document mixing {@code 201.4}
 * and {@code 2026-03-04T21:40} has no defensible ordering, so the
 * mode is a document-level declaration and every entry position is
 * read against it. Mixing is a modelling error, not a feature.
 *
 * @param mode      number line or absolute date-times.
 * @param unit      free-form unit suffix for tick labels ({@code "Ma"},
 *                  {@code "ka"}, {@code "yr BP"}, {@code "min"}).
 *                  Deliberately not an enum: the set of units people
 *                  put on a timeline is open, and an enum would turn
 *                  every new one into a code change. {@code null} in
 *                  {@link TimelineAxisMode#DATETIME} mode.
 * @param direction whether a larger number means later
 *                  ({@link TimelineDirection#FORWARD}) or earlier
 *                  ({@link TimelineDirection#AGO}). Geological and
 *                  archaeological scales count backwards from the
 *                  present; without this the Jurassic renders
 *                  mirror-imaged.
 * @param from      optional viewport lower bound (in axis values).
 *                  When absent the renderer fits the entries.
 * @param to        optional viewport upper bound.
 * @param label     optional axis caption shown under the ruler.
 * @param extra     unknown axis keys, passthrough.
 */
public record TimelineAxis(
        TimelineAxisMode mode,
        @Nullable String unit,
        TimelineDirection direction,
        @Nullable String from,
        @Nullable String to,
        @Nullable String label,
        Map<String, Object> extra) {

    public TimelineAxis {
        if (mode == null) mode = TimelineAxisMode.NUMERIC;
        if (direction == null) direction = TimelineDirection.FORWARD;
        if (extra == null) extra = new LinkedHashMap<>();
    }

    /** Default axis for a document that declares none: a plain, forward number line. */
    public static TimelineAxis defaults() {
        return new TimelineAxis(
                TimelineAxisMode.NUMERIC, null, TimelineDirection.FORWARD,
                null, null, null, new LinkedHashMap<>());
    }

    /** Mode of the axis — see {@link TimelineAxis}. */
    public enum TimelineAxisMode {
        /** Bare numbers with a {@link TimelineAxis#unit()} suffix. */
        NUMERIC,
        /** ISO-8601 dates / date-times, rendered on a calendar-aware ruler. */
        DATETIME;

        /**
         * Lenient wire parse. An unknown or missing value falls back
         * to {@link #NUMERIC} rather than failing the document —
         * a typo'd mode should render something the author can see
         * and fix, and the kind validator reports it separately.
         */
        public static TimelineAxisMode fromWire(@Nullable String raw) {
            if (raw == null) return NUMERIC;
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "datetime", "date-time", "date", "time", "absolute" -> DATETIME;
                default -> NUMERIC;
            };
        }
    }

    /** Reading direction of the numeric axis — see {@link TimelineAxis}. */
    public enum TimelineDirection {
        /** Larger number = later. Ordinary counting. */
        FORWARD,
        /** Larger number = earlier. "Millions of years ago", "days before the incident". */
        AGO;

        /** Lenient wire parse; unknown values mean {@link #FORWARD}. */
        public static TimelineDirection fromWire(@Nullable String raw) {
            if (raw == null) return FORWARD;
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "ago", "backward", "backwards", "bp", "reverse" -> AGO;
                default -> FORWARD;
            };
        }
    }

    /** Lower-case wire token for {@link #mode}. */
    public String modeWire() {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    /** Lower-case wire token for {@link #direction}. */
    public String directionWire() {
        return direction.name().toLowerCase(Locale.ROOT);
    }
}
