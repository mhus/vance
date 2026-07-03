package de.mhus.vance.addon.brain.workbook;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/**
 * Response body for {@code GET /brain/{tenant}/addon/workbook/documents/search}.
 * Used by the link picker to find any document in the current project
 * by name / path substring (recursive).
 */
@GenerateTypeScript("workbook")
public record WorkbookDocumentSearchResponse(
        List<WorkbookDocumentItem> items,
        long total) {}
