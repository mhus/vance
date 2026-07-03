package de.mhus.vance.addon.brain.workbook;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * Request body for {@code POST /brain/{tenant}/addon/workbook/page}. Only
 * {@code title} is required; {@code slug} defaults to the slugged title,
 * {@code section} defaults to the workbook root (top-level page).
 */
@GenerateTypeScript("workbook")
public record WorkbookCreatePageRequest(
        String title,
        @Nullable String description,
        @Nullable String section,
        @Nullable String slug) {}
