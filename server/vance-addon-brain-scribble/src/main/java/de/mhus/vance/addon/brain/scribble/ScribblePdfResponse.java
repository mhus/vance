package de.mhus.vance.addon.brain.scribble;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/**
 * Wire DTO for a scribble PDF export — where the artifact landed, how many
 * sheets it carries and the titles of disabled sheets that were skipped
 * (book export only; empty for a single sheet).
 */
@GenerateTypeScript("scribble")
public record ScribblePdfResponse(String pdfPath, int pageCount, List<String> skippedSheets) {}
