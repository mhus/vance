package de.mhus.vance.brain.tools.report;

import de.mhus.vance.shared.document.FrontMatter;
import org.jspecify.annotations.Nullable;

/**
 * Extracts the {@code theme:} and {@code css:} front-matter keys from a
 * markdown source and returns the body with the front matter stripped.
 *
 * <p>The PDF renderer feeds its input verbatim to commonmark-java, and a
 * surviving {@code ---\ntitle: …\ntheme: …\n---} block would render as a
 * setext H2 ("theme: …" as the heading text). That is harmless but ugly,
 * and — more importantly — the {@code theme:}/{@code css:} keys are
 * metadata, not report content. So this helper removes the front matter
 * from the body that goes to the renderer and hands the two style keys
 * to the caller so they can populate {@link MarkdownReportContext}.
 *
 * <p>Non-markdown text (YAML, JSON, plain text) has no front matter in
 * the Vance sense; {@link FrontMatter#parse} only recognises the
 * {@code ---}-fenced block at the very top, so those inputs pass through
 * untouched and the result carries {@code null} theme/css — the default
 * theme applies. That is correct: a YAML file rendered to PDF is a
 * plain-text dump, not a themed report.
 *
 * <p>The {@code $meta:} block Vance documents use for their own metadata
 * lives in the same front-matter fence and is consumed here as a flat
 * key (its nested YAML value is not interpreted by the flat
 * {@link FrontMatter} parser — it just becomes the empty string, which
 * is irrelevant because we never read it). It is stripped from the body
 * along with {@code theme:}/{@code css:}, so the PDF no longer shows the
 * {@code $meta:} block as a phantom H2 either.
 *
 * @param source raw markdown source, possibly with a front-matter fence
 * @return the body with front matter removed, plus the {@code theme} and
 *         {@code css} values (either may be {@code null} when absent or
 *         blank); never returns {@code null} — a {@code null} source
 *         becomes an empty body with both styles {@code null}.
 */
public record ReportFrontMatter(String body, @Nullable String theme, @Nullable String css) {

    public static ReportFrontMatter parse(@Nullable String source) {
        if (source == null || source.isEmpty()) {
            return new ReportFrontMatter("", null, null);
        }
        FrontMatter fm = FrontMatter.parse(source);
        if (!fm.hasHeader()) {
            return new ReportFrontMatter(source, null, null);
        }
        String theme = trimToNull(fm.get("theme"));
        String css = trimToNull(fm.get("css"));
        return new ReportFrontMatter(fm.body(), theme, css);
    }

    private static @Nullable String trimToNull(@Nullable String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
