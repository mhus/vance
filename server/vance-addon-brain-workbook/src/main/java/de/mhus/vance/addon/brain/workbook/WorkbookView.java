package de.mhus.vance.addon.brain.workbook;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Wire DTO returned by the workbook REST scan endpoint. */
@GenerateTypeScript("workbook")
public record WorkbookView(
        String folder,
        @Nullable String title,
        @Nullable String description,
        @Nullable String landingPagePath,
        @Nullable String landingPageId,
        @Nullable String indexPagePath,
        @Nullable String indexPageId,
        List<WorkbookPageView> pages) {}
