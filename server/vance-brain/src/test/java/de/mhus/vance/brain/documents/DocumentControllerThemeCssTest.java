package de.mhus.vance.brain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.tools.report.CssSanitizer;
import de.mhus.vance.brain.tools.report.CssScopePrefixer;
import de.mhus.vance.brain.tools.report.MarkdownReportService;
import de.mhus.vance.brain.tools.report.ReportThemeResolver;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@link DocumentController#themeCss} — the web-preview CSS endpoint. It
 * reads a markdown document, assembles its three-layer theme stylesheet,
 * filters and scopes it, and serves it as {@code text/css}. These tests
 * pin the contract: markdown gate (empty CSS for non-markdown), READ
 * enforcement, front matter → resolver → sanitize → scope pipeline, and
 * the {@code Content-Type} / {@code Cache-Control} response headers.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentControllerThemeCssTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "proj";

    @Mock private DocumentService documentService;
    @Mock private RequestAuthority authority;
    @Mock private MarkdownReportService markdownReportService;
    @Mock private ReportThemeResolver reportThemeResolver;
    @Mock private HttpServletRequest httpRequest;

    private DocumentController controller;

    @BeforeEach
    void setUp() {
        controller = new DocumentController(
                documentService, authority, markdownReportService, reportThemeResolver);
        when(httpRequest.getAttribute(AccessFilterBase.ATTR_USERNAME)).thenReturn("alice");
        when(authority.contextOf(httpRequest))
                .thenReturn(SecurityContext.user("alice", TENANT, List.of()));
    }

    @Test
    void themeCss_notFound_throws404() {
        when(documentService.findById("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.themeCss(TENANT, "missing", httpRequest))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void themeCss_tenantMismatch_throws404() {
        DocumentDocument otherTenantDoc = DocumentDocument.builder()
                .id("doc-1").tenantId("other").projectId(PROJECT)
                .path("notes/x.md").name("x.md")
                .mimeType("text/markdown").build();
        when(documentService.findById("doc-1")).thenReturn(Optional.of(otherTenantDoc));
        assertThatThrownBy(() -> controller.themeCss(TENANT, "doc-1", httpRequest))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void themeCss_nonMarkdown_returnsEmptyCss_200() {
        DocumentDocument doc = doc("doc-1", "apps/main.js", "application/javascript");
        when(documentService.findById("doc-1")).thenReturn(Optional.of(doc));

        ResponseEntity<String> resp = controller.themeCss(TENANT, "doc-1", httpRequest);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEmpty();
        assertThat(resp.getHeaders().getContentType())
            .isEqualTo(MediaType.valueOf("text/css;charset=utf-8"));
        // Non-markdown short-circuits before content is read.
        verify(documentService, never()).readContent(any());
        verify(reportThemeResolver, never()).resolveStylesheet(any(), any(), any(), any());
    }

    @Test
    void themeCss_markdownNoTheme_returnsDefaultScoped() {
        DocumentDocument doc = doc("doc-1", "notes/x.md", "text/markdown");
        when(documentService.findById("doc-1")).thenReturn(Optional.of(doc));
        when(documentService.readContent(doc)).thenReturn("# Hello\n");
        // Resolver returns the default layer (always loaded).
        when(reportThemeResolver.resolveStylesheet(eq(TENANT), eq(PROJECT), any(), any()))
            .thenReturn("body { font-family: serif; }\nh1 { color: black; }");

        ResponseEntity<String> resp = controller.themeCss(TENANT, "doc-1", httpRequest);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String css = resp.getBody();
        // Every selector is scoped under .markdown-document-preview.
        assertThat(css).contains(CssScopePrefixer.SCOPE + " body { font-family: serif; }");
        assertThat(css).contains(CssScopePrefixer.SCOPE + " h1 { color: black; }");
        assertThat(resp.getHeaders().getContentType())
            .isEqualTo(MediaType.valueOf("text/css;charset=utf-8"));
        assertThat(resp.getHeaders().getCacheControl()).contains("max-age=60");
    }

    @Test
    void themeCss_withThemeFrontmatter_passesThemeToResolver() {
        DocumentDocument doc = doc("doc-1", "notes/x.md", "text/markdown");
        when(documentService.findById("doc-1")).thenReturn(Optional.of(doc));
        when(documentService.readContent(doc))
            .thenReturn("---\ntheme: acme\n---\n# Hello\n");
        when(reportThemeResolver.resolveStylesheet(eq(TENANT), eq(PROJECT), eq("acme"), any()))
            .thenReturn("h1 { color: #8a6d1a; }");

        ResponseEntity<String> resp = controller.themeCss(TENANT, "doc-1", httpRequest);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody())
            .contains(CssScopePrefixer.SCOPE + " h1 { color: #8a6d1a; }");
        verify(reportThemeResolver).resolveStylesheet(TENANT, PROJECT, "acme", null);
    }

    @Test
    void themeCss_withCssRefFrontmatter_passesCssToResolver() {
        DocumentDocument doc = doc("doc-1", "notes/x.md", "text/markdown");
        when(documentService.findById("doc-1")).thenReturn(Optional.of(doc));
        when(documentService.readContent(doc))
            .thenReturn("---\ntheme: acme\ncss: vance:/styles/x.css\n---\n# Hello\n");
        when(reportThemeResolver.resolveStylesheet(eq(TENANT), eq(PROJECT), eq("acme"), eq("vance:/styles/x.css")))
            .thenReturn("h1 { color: red; }");

        ResponseEntity<String> resp = controller.themeCss(TENANT, "doc-1", httpRequest);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody())
            .contains(CssScopePrefixer.SCOPE + " h1 { color: red; }");
    }

    @Test
    void themeCss_externalUrlInCss_filteredOut() {
        DocumentDocument doc = doc("doc-1", "notes/x.md", "text/markdown");
        when(documentService.findById("doc-1")).thenReturn(Optional.of(doc));
        when(documentService.readContent(doc)).thenReturn("# Hello\n");
        // Resolver returns CSS with a dangerous external url() inside a real rule.
        when(reportThemeResolver.resolveStylesheet(any(), any(), any(), any()))
            .thenReturn(".note { background: url('https://evil/x.png') red; }\nh1 { color: red; }");

        ResponseEntity<String> resp = controller.themeCss(TENANT, "doc-1", httpRequest);

        assertThat(resp.getBody())
            .doesNotContain("evil")
            .contains("url()")
            .contains(CssScopePrefixer.SCOPE + " h1 { color: red; }");
    }

    @Test
    void themeCss_atImport_filteredOut() {
        DocumentDocument doc = doc("doc-1", "notes/x.md", "text/markdown");
        when(documentService.findById("doc-1")).thenReturn(Optional.of(doc));
        when(documentService.readContent(doc)).thenReturn("# Hello\n");
        when(reportThemeResolver.resolveStylesheet(any(), any(), any(), any()))
            .thenReturn("@import 'https://evil/x.css';\nh1 { color: red; }");

        ResponseEntity<String> resp = controller.themeCss(TENANT, "doc-1", httpRequest);

        assertThat(resp.getBody())
            .doesNotContain("@import")
            .doesNotContain("evil");
    }

    @Test
    void themeCss_readEnforced() {
        DocumentDocument doc = doc("doc-1", "notes/x.md", "text/markdown");
        when(documentService.findById("doc-1")).thenReturn(Optional.of(doc));
        when(documentService.readContent(doc)).thenReturn("# Hello\n");
        when(reportThemeResolver.resolveStylesheet(any(), any(), any(), any()))
            .thenReturn("h1 { color: red; }");

        controller.themeCss(TENANT, "doc-1", httpRequest);

        verify(authority).enforce(eq(httpRequest), any(), eq(Action.READ));
    }

    private static DocumentDocument doc(String id, String path, String mime) {
        return DocumentDocument.builder()
                .id(id).tenantId(TENANT).projectId(PROJECT)
                .path(path).name(path.substring(path.lastIndexOf('/') + 1))
                .mimeType(mime)
                .build();
    }
}
