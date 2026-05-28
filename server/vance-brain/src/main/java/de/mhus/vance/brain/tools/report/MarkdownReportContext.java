package de.mhus.vance.brain.tools.report;

import org.jspecify.annotations.Nullable;

/**
 * Input for a {@link MarkdownReportRenderer}. Holds the source
 * markdown plus the metadata (title, author) that renderers may
 * surface in headers / cover-pages and the scope info that vance:-
 * link resolution needs.
 *
 * @param markdown      report source, CommonMark + GFM-tables
 * @param title         display title (used in PDF metadata, DOCX
 *                      core properties, header). Falls back to the
 *                      first H1 in {@code markdown} or to a default
 *                      string when neither is present
 * @param author        document author; populates the PDF info dict
 *                      and the DOCX core properties
 * @param tenantId      caller's tenant — used for vance:-link
 *                      resolution against the project scope
 * @param projectName   caller's active project name (the
 *                      {@link de.mhus.vance.shared.project.ProjectDocument#getName()})
 */
public record MarkdownReportContext(
        String markdown,
        @Nullable String title,
        @Nullable String author,
        String tenantId,
        String projectName) {
}
