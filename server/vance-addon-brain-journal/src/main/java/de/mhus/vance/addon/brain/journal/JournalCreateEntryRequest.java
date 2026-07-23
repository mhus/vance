package de.mhus.vance.addon.brain.journal;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Request body for {@code PUT /brain/{tenant}/addon/journal/entry}. Every
 * field is optional: {@code date} defaults to today; the remaining fields
 * leave an existing entry's value untouched when {@code null}.
 */
@GenerateTypeScript("journal")
public record JournalCreateEntryRequest(
        @Nullable String date,
        @Nullable String title,
        @Nullable String mood,
        @Nullable List<String> tags,
        @Nullable String body) {}
