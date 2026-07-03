package de.mhus.vance.addon.brain.workbook;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/**
 * Response body for {@code GET /brain/{tenant}/addon/workbook/images}.
 * Slim image-search projection used by the asset picker — the embedded
 * channel never needs the full document metadata.
 */
@GenerateTypeScript("workbook")
public record WorkbookImageSearchResponse(
        List<WorkbookImageItem> items,
        long total) {}
