package de.mhus.vance.addon.brain.journal;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/** Wire DTO for the "on this day" retrospective — entries from earlier years. */
@GenerateTypeScript("journal")
public record JournalOnThisDayView(
        String date,
        List<JournalEntryView> entries) {}
