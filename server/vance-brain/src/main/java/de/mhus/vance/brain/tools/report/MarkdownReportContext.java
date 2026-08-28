package de.mhus.vance.brain.tools.report;

import de.mhus.vance.shared.permission.SecurityContext;
import org.jspecify.annotations.Nullable;

/**
 * Input for a {@link MarkdownReportRenderer}. Holds the source
 * markdown plus the metadata (title, author) that renderers may
 * surface in headers / cover-pages and the scope info that vance:-
 * link resolution needs.
 *
 * @param markdown      report source, CommonMark + GFM-tables. The
 *                      caller is expected to have stripped any
 *                      front matter before passing it here — the
 *                      PDF renderer feeds this string verbatim to
 *                      commonmark, and a surviving
 *                      {@code ---\ntheme: …\n---} block would render
 *                      as a setext H2. {@link ReportThemeResolver}
 *                      is the one place front matter is parsed, and
 *                      it consumes {@code theme:}/{@code css:} from
 *                      the original source before this record is built.
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
 * @param theme         optional report-theme name (matches
 *                      {@code _vance/report-themes/<name>.css} via the
 *                      document cascade). PDF-only; DOCX/ODT renderers
 *                      ignore it. Null/blank = default theme.
 * @param css           optional {@code vance:} document reference (or
 *                      bare path) to an additional CSS stylesheet in
 *                      the caller's project. PDF-only; DOCX/ODT
 *                      renderers ignore it. Null/blank = no css layer.
 *                      Loaded after {@code theme} so its rules win
 *                      the CSS cascade.
 * @param subject       who is asking. A {@code css:} reference may name
 *                      another project ({@code vance://other/x.css}) and
 *                      the resolver is pure computation, so the
 *                      authorization has to travel with the request —
 *                      {@link ReportThemeResolver} checks READ on the
 *                      resolved document against this. {@code null} means
 *                      "no subject known" and drops the css layer rather
 *                      than reading on nobody's behalf; there is
 *                      deliberately no convenience constructor that takes
 *                      a {@code css} without one.
 */
public record MarkdownReportContext(
        String markdown,
        @Nullable String title,
        @Nullable String author,
        String tenantId,
        String projectName,
        @Nullable String theme,
        @Nullable String css,
        @Nullable SecurityContext subject) {

    /**
     * Convenience constructor for callers that don't use the
     * {@code theme}/{@code css} front-matter fields — keeps the
     * original five-argument call sites working. Equivalent to
     * passing {@code null} for all three.
     */
    public MarkdownReportContext(
            String markdown,
            @Nullable String title,
            @Nullable String author,
            String tenantId,
            String projectName) {
        this(markdown, title, author, tenantId, projectName, null, null, null);
    }
}
