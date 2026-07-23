package de.mhus.vance.addon.brain.journal;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Wire DTO for a single entry including its Markdown body (editor load). */
@GenerateTypeScript("journal")
public record JournalEntryContentView(
        String id,
        String path,
        String date,
        String title,
        @Nullable String mood,
        List<String> tags,
        String body) {}
