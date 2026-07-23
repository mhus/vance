package de.mhus.vance.addon.brain.journal;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Wire DTO mirroring {@link JournalStatsBuilder.Stats}. */
@GenerateTypeScript("journal")
public record JournalStatsView(
        int totalEntries,
        @Nullable String firstEntry,
        @Nullable String lastEntry,
        int currentStreak,
        int longestStreak,
        int entriesThisMonth,
        int entriesThisYear,
        Map<String, Integer> moodDistribution,
        Map<String, Integer> tagHistogram) {}
