package de.mhus.vance.addon.brain.workbook;

import org.jspecify.annotations.Nullable;

/**
 * Request body for {@code POST /brain/{tenant}/addon/workbook/form/create}.
 * Creates a new form data document ({@code kind: records}) in the app
 * folder. {@code name} is slugified into the file name; {@code title} is
 * the display title (falls back to {@code name}).
 */
public record WorkbookFormCreateRequest(
        String name,
        @Nullable String title) {}
