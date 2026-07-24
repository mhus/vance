package de.mhus.vance.addon.brain.finance;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Wire DTO for a full finance-tree — the editor loads/saves this shape. */
@GenerateTypeScript("finance")
public record FinanceTreeDto(
        int version,
        @Nullable String title,
        @Nullable String description,
        @Nullable FinanceNodeDto root) {}
