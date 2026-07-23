package de.mhus.vance.addon.brain.journal;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/** Wire DTO returned by the journal search endpoint. */
@GenerateTypeScript("journal")
public record JournalSearchResponse(
        List<JournalHitView> items,
        long total) {}
