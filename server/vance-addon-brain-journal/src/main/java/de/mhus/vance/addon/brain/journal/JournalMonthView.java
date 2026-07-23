package de.mhus.vance.addon.brain.journal;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/**
 * Wire DTO for the calendar month mask: which day-of-month numbers carry
 * an entry in the given year + month.
 */
@GenerateTypeScript("journal")
public record JournalMonthView(
        int year,
        int month,
        List<Integer> days) {}
