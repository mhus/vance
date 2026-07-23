package de.mhus.vance.addon.brain.journal;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Wire DTO for one search hit — entry identity plus a snippet + score. */
@GenerateTypeScript("journal")
public record JournalHitView(
        String id,
        String path,
        @Nullable String date,
        @Nullable String title,
        @Nullable String mood,
        String snippet,
        int score) {}
