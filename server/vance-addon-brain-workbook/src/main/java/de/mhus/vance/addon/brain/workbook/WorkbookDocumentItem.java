package de.mhus.vance.addon.brain.workbook;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * Slim document projection — one entry of a workbook document search
 * (link picker). Carries enough metadata for the picker to show a
 * meaningful row (title, kind badge, path) and build a
 * {@code vance:/<path>?kind=<kind>} URI.
 */
@GenerateTypeScript("workbook")
public record WorkbookDocumentItem(
        String id,
        String path,
        @Nullable String title,
        @Nullable String kind,
        @Nullable String mimeType) {}
