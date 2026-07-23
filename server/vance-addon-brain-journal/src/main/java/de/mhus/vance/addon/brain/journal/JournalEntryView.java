package de.mhus.vance.addon.brain.journal;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Wire DTO for one journal entry in list / calendar contexts (no body). */
@GenerateTypeScript("journal")
public record JournalEntryView(
        String id,
        String path,
        String date,
        String title,
        @Nullable String mood,
        List<String> tags) {}
