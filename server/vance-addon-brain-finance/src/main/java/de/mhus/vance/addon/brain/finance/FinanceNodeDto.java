package de.mhus.vance.addon.brain.finance;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Wire DTO for a finance node — display fields, value records, recursive children. */
@GenerateTypeScript("finance")
public record FinanceNodeDto(
        String name,
        @Nullable String title,
        @Nullable String icon,
        @Nullable String color,
        int sign,
        @Nullable String description,
        @Nullable String notesRef,
        List<FinanceValueDto> values,
        List<FinanceNodeDto> children) {}
