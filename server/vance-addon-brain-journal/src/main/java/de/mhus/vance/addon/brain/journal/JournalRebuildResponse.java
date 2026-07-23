package de.mhus.vance.addon.brain.journal;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Wire DTO returned by the journal rebuild endpoint. */
@GenerateTypeScript("journal")
public record JournalRebuildResponse(
        String folder,
        @Nullable String indexPath,
        @Nullable String statsPath,
        int entryCount,
        int currentStreak,
        int longestStreak) {}
