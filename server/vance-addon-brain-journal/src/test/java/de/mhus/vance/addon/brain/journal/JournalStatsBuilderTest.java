package de.mhus.vance.addon.brain.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** Streak edge cases for {@link JournalStatsBuilder} — pure, no Mongo/clock. */
class JournalStatsBuilderTest {

    private static TreeSet<LocalDate> dates(String... iso) {
        TreeSet<LocalDate> s = new TreeSet<>();
        for (String d : iso) s.add(LocalDate.parse(d));
        return s;
    }

    @Test
    void currentStreak_countsBackFromToday() {
        LocalDate today = LocalDate.parse("2026-07-24");
        assertThat(JournalStatsBuilder.currentStreak(
                dates("2026-07-22", "2026-07-23", "2026-07-24"), today))
                .isEqualTo(3);
    }

    @Test
    void currentStreak_todayOpen_doesNotBreak_whenYesterdayPresent() {
        LocalDate today = LocalDate.parse("2026-07-24");
        // no entry today, but yesterday + before → streak counts from yesterday
        assertThat(JournalStatsBuilder.currentStreak(
                dates("2026-07-22", "2026-07-23"), today))
                .isEqualTo(2);
    }

    @Test
    void currentStreak_gapBeforeYesterday_endsStreak() {
        LocalDate today = LocalDate.parse("2026-07-24");
        // today + a gap (missing 23) → only today counts
        assertThat(JournalStatsBuilder.currentStreak(
                dates("2026-07-20", "2026-07-24"), today))
                .isEqualTo(1);
    }

    @Test
    void currentStreak_neitherTodayNorYesterday_isZero() {
        LocalDate today = LocalDate.parse("2026-07-24");
        assertThat(JournalStatsBuilder.currentStreak(
                dates("2026-07-20", "2026-07-21"), today))
                .isZero();
    }

    @Test
    void currentStreak_empty_isZero() {
        assertThat(JournalStatsBuilder.currentStreak(dates(), LocalDate.parse("2026-07-24")))
                .isZero();
    }

    @Test
    void longestStreak_findsLongestConsecutiveRun() {
        assertThat(JournalStatsBuilder.longestStreak(
                dates("2026-01-01", "2026-01-02", "2026-01-03",   // run of 3
                        "2026-02-01",                              // isolated
                        "2026-03-10", "2026-03-11")))              // run of 2
                .isEqualTo(3);
    }

    @Test
    void longestStreak_empty_isZero() {
        assertThat(JournalStatsBuilder.longestStreak(dates())).isZero();
    }
}
