package de.mhus.vance.addon.brain.calendar;

import de.mhus.vance.addon.brain.calendar.CalendarsAppConfig;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Detect time-overlap conflicts between calendar occurrences across
 * an entire calendar-app folder. Pure-function helper used by
 * {@code calendar_conflicts} and {@code app_rebuild}.
 *
 * <p>Two occurrences conflict when their time ranges overlap. Point
 * events (no {@code end}) are treated as zero-duration: they conflict
 * only with events whose range strictly contains them.
 *
 * <p>Filters honoured:
 * <ul>
 *   <li>{@link CalendarsAppConfig.ConflictsConfig#ignoreWithinTags}:
 *       if both occurrences carry the same ignore-tag, skip the
 *       pair (typical use: {@code private} so two vacation events
 *       in the same family calendar don't keep flagging).</li>
 *   <li>{@link CalendarsAppConfig.ConflictsConfig#ignoreAllDayOverlapsBetweenLanes}:
 *       all-day events in different lanes are intentionally
 *       compatible (vacation in design vs. launch day in backend
 *       isn't a conflict).</li>
 * </ul>
 */
public final class ConflictDetector {

    private ConflictDetector() { }

    /** A located occurrence — event + lane + sourcePath + parsed start/end. */
    public record LocatedOccurrence(
            RecurrenceExpander.Occurrence occurrence,
            String lane,
            String sourcePath) {

        public LocalDateTime start() { return occurrence.start(); }

        public LocalDateTime end() {
            return occurrence.end() != null
                    ? occurrence.end()
                    : occurrence.start();
        }

        public boolean allDay() { return occurrence.allDay(); }
    }

    public record Conflict(
            LocatedOccurrence a,
            LocatedOccurrence b,
            LocalDateTime overlapStart,
            LocalDateTime overlapEnd) { }

    /**
     * Detect all pairwise conflicts. {@code O(n²)} but with a sorted
     * scan that breaks early — in practice O(n + conflicts) for
     * mostly-non-overlapping input. For ~hundreds of occurrences
     * this is irrelevant; tune only if it ever becomes a bottleneck.
     */
    public static List<Conflict> detect(List<LocatedOccurrence> occs,
                                        CalendarsAppConfig.ConflictsConfig cfg) {
        List<LocatedOccurrence> sorted = new ArrayList<>(occs);
        sorted.sort(Comparator.comparing(LocatedOccurrence::start));

        List<Conflict> out = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            LocatedOccurrence a = sorted.get(i);
            for (int j = i + 1; j < sorted.size(); j++) {
                LocatedOccurrence b = sorted.get(j);
                if (b.start().isAfter(a.end())) break; // sorted — no more overlaps
                if (b.start().equals(a.end()) && !b.allDay() && !a.allDay()) {
                    // back-to-back timed events touch at a single
                    // moment — that's not a conflict (a "10-11"
                    // meeting and an "11-12" meeting are fine).
                    continue;
                }

                if (cfg.ignoreAllDayOverlapsBetweenLanes()
                        && a.allDay() && b.allDay()
                        && !a.lane().equals(b.lane())) {
                    continue;
                }
                if (shareIgnoreTag(a, b, cfg.ignoreWithinTags())) continue;

                LocalDateTime start = a.start().isAfter(b.start()) ? a.start() : b.start();
                LocalDateTime end = a.end().isBefore(b.end()) ? a.end() : b.end();
                out.add(new Conflict(a, b, start, end));
            }
        }
        return out;
    }

    private static boolean shareIgnoreTag(LocatedOccurrence a, LocatedOccurrence b,
                                          List<String> ignoreTags) {
        if (ignoreTags.isEmpty()) return false;
        List<String> aTags = a.occurrence().event().tags();
        List<String> bTags = b.occurrence().event().tags();
        for (String t : ignoreTags) {
            if (aTags.contains(t) && bTags.contains(t)) return true;
        }
        return false;
    }
}
