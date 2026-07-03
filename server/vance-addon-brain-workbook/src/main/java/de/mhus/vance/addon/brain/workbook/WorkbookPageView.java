package de.mhus.vance.addon.brain.workbook;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Wire DTO for one page inside a workbook. */
@GenerateTypeScript("workbook")
public record WorkbookPageView(
        String id,
        String path,
        String relativePath,
        String section,
        String title,
        @Nullable String description,
        @Nullable String icon,
        @Nullable Double sortIndex) {}
