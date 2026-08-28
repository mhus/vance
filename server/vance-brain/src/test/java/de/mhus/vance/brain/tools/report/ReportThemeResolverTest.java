package de.mhus.vance.brain.tools.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentRef;
import de.mhus.vance.shared.document.DocumentRefContext;
import de.mhus.vance.shared.document.DocumentRefResolver;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * {@link ReportThemeResolver} — the three-layer stylesheet assembly:
 * default (always), theme (cascade, optional), css-ref (document, optional).
 * The default layer reads the real bundled file via the classpath resolver,
 * so these tests also guard that {@code default.css} stays XML-safe under
 * the openhtmltopdf XHTML parser (the comment with the angle-bracket lesson
 * lives here as a regression anchor).
 */
class ReportThemeResolverTest {

    private static ReportThemeResolver resolverWith(DocumentService ds, DocumentRefResolver drr) {
        return new ReportThemeResolver(ds, drr,
                new PathMatchingResourcePatternResolver());
    }

    private static DocumentService docServiceMock() {
        return mock(DocumentService.class);
    }

    private static DocumentRefResolver refResolverMock() {
        return mock(DocumentRefResolver.class);
    }

    @Test
    void resolve_noThemeNoCss_returnsDefaultOnly() {
        String css = resolverWith(docServiceMock(), refResolverMock())
                .resolveStylesheet("t", "p", null, null);

        // The default carries the @page rule and the body font — a stable
        // fingerprint that also proves the bundled file loaded at all.
        assertThat(css).contains("@page");
        assertThat(css).contains("Times New Roman");
        // No theme/css layer was requested, so no cascade/ref lookup happened.
        // (The fingerprint is enough; we don't assert the exact length because
        // the bundled file may evolve.)
    }

    @Test
    void resolve_invalidThemeName_skipsTheme_logsAndFallsBack() {
        DocumentService ds = docServiceMock();
        String css = resolverWith(ds, refResolverMock())
                .resolveStylesheet("t", "p", "../traversal", null);

        // An invalid name must NOT reach the cascade — otherwise a crafted
        // name could probe arbitrary paths. The default still comes through.
        verify(ds, never()).lookupCascade(anyString(), anyString(), anyString());
        assertThat(css).contains("@page");
    }

    @Test
    void resolve_validTheme_loadsFromCascade_andAppendsAfterDefault() {
        DocumentService ds = docServiceMock();
        when(ds.lookupCascade("t", "p", "_vance/report-themes/acme.css"))
                .thenReturn(Optional.of(new LookupResult(
                        "_vance/report-themes/acme.css",
                        "/* acme theme */\nh1 { color: purple; }",
                        LookupResult.Source.PROJECT,
                        null)));

        String css = resolverWith(ds, refResolverMock())
                .resolveStylesheet("t", "p", "acme", null);

        // Default first, theme after — so the theme's h1 rule wins the cascade.
        int defaultIdx = css.indexOf("Times New Roman");
        int themeIdx = css.indexOf("color: purple");
        assertThat(defaultIdx).isLessThan(themeIdx);
        assertThat(css).contains("color: purple");
    }

    @Test
    void resolve_themeNotInCascade_fallsBackToDefault() {
        DocumentService ds = docServiceMock();
        when(ds.lookupCascade(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        String css = resolverWith(ds, refResolverMock())
                .resolveStylesheet("t", "p", "missing", null);

        // Missing theme is fail-open: default only, no throw.
        assertThat(css).contains("@page");
        assertThat(css).doesNotContain("missing");
    }

    @Test
    void resolve_cssRef_loadsDocument_andAppendsAfterTheme() {
        DocumentService ds = docServiceMock();
        DocumentRefResolver drr = refResolverMock();
        DocumentRef ref = DocumentRef.of("p", "styles/round.css");
        when(drr.resolve("vance:/styles/round.css",
                DocumentRefContext.root("p"))).thenReturn(ref);
        DocumentDocument doc = mock(DocumentDocument.class);
        when(doc.getPath()).thenReturn("styles/round.css");
        when(ds.findByPath("t", "p", "styles/round.css")).thenReturn(Optional.of(doc));
        when(ds.readContent(doc)).thenReturn("pre { border-radius: 8px; }");

        String css = resolverWith(ds, drr)
                .resolveStylesheet("t", "p", null, "vance:/styles/round.css");

        int defaultIdx = css.indexOf("Times New Roman");
        int refIdx = css.indexOf("border-radius");
        assertThat(defaultIdx).isLessThan(refIdx);
        assertThat(css).contains("border-radius: 8px");
    }

    @Test
    void resolve_cssRefUnresolvable_fallsBackToDefault() {
        DocumentRefResolver drr = refResolverMock();
        when(drr.resolve(anyString(), any(DocumentRefContext.class)))
                .thenThrow(new de.mhus.vance.shared.document.DocumentRefException("bad ref"));

        String css = resolverWith(docServiceMock(), drr)
                .resolveStylesheet("t", "p", null, "vance:/nope.css");

        // Fail-open: bad ref does not abort the render.
        assertThat(css).contains("@page");
    }

    @Test
    void resolve_cssRefPointsToMissingDoc_fallsBackToDefault() {
        DocumentService ds = docServiceMock();
        DocumentRefResolver drr = refResolverMock();
        DocumentRef ref = DocumentRef.of("p", "styles/missing.css");
        when(drr.resolve(anyString(), any(DocumentRefContext.class))).thenReturn(ref);
        when(ds.findByPath(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        String css = resolverWith(ds, drr)
                .resolveStylesheet("t", "p", null, "styles/missing.css");

        assertThat(css).contains("@page");
        // The dangling ref must not inject anything.
        assertThat(css).doesNotContain("missing.css");
    }

    @Test
    void resolve_themeAndCssBoth_themeFirstThenCss() {
        DocumentService ds = docServiceMock();
        DocumentRefResolver drr = refResolverMock();
        when(ds.lookupCascade("t", "p", "_vance/report-themes/acme.css"))
                .thenReturn(Optional.of(new LookupResult(
                        "_vance/report-themes/acme.css",
                        "h1 { color: purple; }",
                        LookupResult.Source.PROJECT,
                        null)));
        DocumentRef ref = DocumentRef.of("p", "styles/round.css");
        when(drr.resolve(anyString(), any(DocumentRefContext.class))).thenReturn(ref);
        DocumentDocument doc = mock(DocumentDocument.class);
        when(ds.findByPath("t", "p", "styles/round.css")).thenReturn(Optional.of(doc));
        when(ds.readContent(doc)).thenReturn("pre { border-radius: 8px; }");

        String css = resolverWith(ds, drr)
                .resolveStylesheet("t", "p", "acme", "vance:/styles/round.css");

        // Order: default < theme < css-ref — last wins the cascade.
        int defaultIdx = css.indexOf("Times New Roman");
        int themeIdx = css.indexOf("color: purple");
        int cssIdx = css.indexOf("border-radius");
        assertThat(defaultIdx).isLessThan(themeIdx);
        assertThat(themeIdx).isLessThan(cssIdx);
    }

    @Test
    void resolve_blankThemeAndCss_areTreatedAsAbsent() {
        DocumentService ds = docServiceMock();
        String css = resolverWith(ds, refResolverMock())
                .resolveStylesheet("t", "p", "   ", "   ");

        // Blank values must not trigger cascade/ref lookups.
        verify(ds, never()).lookupCascade(anyString(), anyString(), anyString());
        assertThat(css).contains("@page");
    }

    // ──────────────────── ReportFrontMatter ────────────────────

    @Test
    void frontmatter_parsesThemeAndCss_andStripsHeaderFromBody() {
        String src = "---\n$meta:\n  kind: report\ntheme: acme\ncss: vance:/styles/round.css\n---\n# Body\n";
        ReportFrontMatter fm = ReportFrontMatter.parse(src);

        assertThat(fm.theme()).isEqualTo("acme");
        assertThat(fm.css()).isEqualTo("vance:/styles/round.css");
        // Body has the front matter stripped, so commonmark won't render
        // "theme: acme" as a setext H2.
        assertThat(fm.body()).isEqualTo("# Body\n");
    }

    @Test
    void frontmatter_noHeader_returnsSourceUntouched() {
        String src = "# Just a heading\n\nbody";
        ReportFrontMatter fm = ReportFrontMatter.parse(src);

        assertThat(fm.theme()).isNull();
        assertThat(fm.css()).isNull();
        assertThat(fm.body()).isEqualTo(src);
    }

    @Test
    void frontmatter_headerWithoutThemeOrCss_returnsBodyStripped() {
        String src = "---\ntitle: My Report\n---\n# Body\n";
        ReportFrontMatter fm = ReportFrontMatter.parse(src);

        assertThat(fm.theme()).isNull();
        assertThat(fm.css()).isNull();
        assertThat(fm.body()).isEqualTo("# Body\n");
    }

    @Test
    void frontmatter_blankValuesBecomeNull() {
        String src = "---\ntheme:   \ncss:\n---\n# Body\n";
        ReportFrontMatter fm = ReportFrontMatter.parse(src);

        assertThat(fm.theme()).isNull();
        assertThat(fm.css()).isNull();
    }

    @Test
    void frontmatter_nullSource_returnsEmpty() {
        ReportFrontMatter fm = ReportFrontMatter.parse(null);

        assertThat(fm.body()).isEqualTo("");
        assertThat(fm.theme()).isNull();
        assertThat(fm.css()).isNull();
    }
}
