package de.mhus.vance.addon.brain.journal;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * Deterministic statistics over a journal's entries: streaks, per-period
 * counts, mood distribution and a tag histogram. The streak math is pure
 * and package-private for unit testing (no Mongo, no clock).
 */
@Component
public class JournalStatsBuilder {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final int TAG_HISTOGRAM_LIMIT = 30;

    /**
     * @param totalEntries    number of entries
     * @param firstEntry      earliest entry date (ISO), or {@code null}
     * @param lastEntry       latest entry date (ISO), or {@code null}
     * @param currentStreak   consecutive days up to today (today-open tolerated)
     * @param longestStreak   longest consecutive run ever
     * @param entriesThisMonth count in the current calendar month
     * @param entriesThisYear  count in the current calendar year
     * @param moodDistribution mood → count (insertion order = preset order then extras)
     * @param tagHistogram     tag → count, top {@value #TAG_HISTOGRAM_LIMIT} by count
     */
    public record Stats(
            int totalEntries,
            String firstEntry,
            String lastEntry,
            int currentStreak,
            int longestStreak,
            int entriesThisMonth,
            int entriesThisYear,
            Map<String, Integer> moodDistribution,
            Map<String, Integer> tagHistogram) {}

    public Stats build(JournalFolderReader.Scan scan, LocalDate today) {
        List<JournalEntry> entries = scan.entries();
        TreeSet<LocalDate> dates = new TreeSet<>();
        Map<String, Integer> moods = new LinkedHashMap<>();
        Map<String, Integer> tags = new LinkedHashMap<>();

        for (JournalEntry e : entries) {
            LocalDate d = parse(e.date());
            if (d != null) dates.add(d);
            if (e.mood() != null && !e.mood().isBlank()) {
                moods.merge(e.mood().trim(), 1, Integer::sum);
            }
            for (String tag : e.tags()) {
                if (tag != null && !tag.isBlank()) tags.merge(tag.trim(), 1, Integer::sum);
            }
        }

        String first = dates.isEmpty() ? null : dates.first().format(ISO);
        String last = dates.isEmpty() ? null : dates.last().format(ISO);
        int thisMonth = 0;
        int thisYear = 0;
        for (LocalDate d : dates) {
            if (d.getYear() == today.getYear()) {
                thisYear++;
                if (d.getMonthValue() == today.getMonthValue()) thisMonth++;
            }
        }

        return new Stats(
                entries.size(), first, last,
                currentStreak(dates, today), longestStreak(dates),
                thisMonth, thisYear,
                moods, topN(tags, TAG_HISTOGRAM_LIMIT));
    }

    /**
     * Consecutive days with an entry, counting back from {@code today}.
     * A missing entry for {@code today} does not break the streak as long
     * as {@code yesterday} has one (the day is still open); any earlier
     * gap ends it. Returns 0 when neither today nor yesterday has an entry.
     */
    static int currentStreak(java.util.NavigableSet<LocalDate> dates, LocalDate today) {
        if (dates.isEmpty()) return 0;
        LocalDate anchor;
        if (dates.contains(today)) {
            anchor = today;
        } else if (dates.contains(today.minusDays(1))) {
            anchor = today.minusDays(1);
        } else {
            return 0;
        }
        int streak = 0;
        LocalDate cursor = anchor;
        while (dates.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    /** Longest run of consecutive calendar days present in {@code dates}. */
    static int longestStreak(java.util.NavigableSet<LocalDate> dates) {
        int best = 0;
        int run = 0;
        LocalDate prev = null;
        for (LocalDate d : dates) {
            if (prev != null && prev.plusDays(1).equals(d)) {
                run++;
            } else {
                run = 1;
            }
            if (run > best) best = run;
            prev = d;
        }
        return best;
    }

    private static Map<String, Integer> topN(Map<String, Integer> counts, int n) {
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Comparator
                .comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed()
                .thenComparing(Map.Entry::getKey));
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(n, sorted.size()); i++) {
            out.put(sorted.get(i).getKey(), sorted.get(i).getValue());
        }
        return out;
    }

    private static LocalDate parse(String iso) {
        try {
            return LocalDate.parse(iso, ISO);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
