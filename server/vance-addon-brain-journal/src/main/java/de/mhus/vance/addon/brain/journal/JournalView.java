package de.mhus.vance.addon.brain.journal;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Wire DTO returned by the journal REST scan endpoint. */
@GenerateTypeScript("journal")
public record JournalView(
        String folder,
        @Nullable String title,
        @Nullable String description,
        String entriesDir,
        List<String> moodPresets,
        JournalStatsView stats,
        List<JournalEntryView> recent) {}
