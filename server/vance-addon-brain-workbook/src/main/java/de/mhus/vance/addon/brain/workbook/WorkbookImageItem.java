package de.mhus.vance.addon.brain.workbook;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Slim image projection — one entry of a workbook image search. */
@GenerateTypeScript("workbook")
public record WorkbookImageItem(
        String id,
        String path,
        String name,
        @Nullable String mimeType) {}
