package de.mhus.vance.addon.brain.workbook;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Wire DTO returned by the workbook rebuild endpoint. */
@GenerateTypeScript("workbook")
public record WorkbookRebuildResponse(
        String folder,
        String indexPath,
        @Nullable String indexLink,
        int pageCount) {}
