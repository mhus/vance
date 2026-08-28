package de.mhus.vance.brain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.tools.report.MarkdownReportContext;
import de.mhus.vance.brain.tools.report.MarkdownReportService;
import de.mhus.vance.brain.tools.report.ReportThemeResolver;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * {@link DocumentController#exportPdf} — the Cortex "File &rarr; Export PDF"
 * endpoint.
 *
 * <p>It reads one document and <b>writes another</b>, and the second half is
 * the one that goes missing: the endpoint's name says "export", so READ on
 * the source looks like the whole question. It is not — a reader would
 * otherwise be able to drop a PDF into a project, or over somebody else's
 * file, through an endpoint that never claims to write.
 *
 * <p>The other thing pinned here is that the subject travels into the render
 * context: a {@code css:} front-matter key may name a document in another
 * project, and {@link ReportThemeResolver} can only check that against a
 * caller it was given.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentControllerExportPdfTest {

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
        when(markdownReportService.render(eq("pdf"), any()))
                .thenReturn(new MarkdownReportService.RenderedReport(
                        "%PDF-1.4".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "application/pdf", "pdf"));
    }

    @Test
    void exportPdf_targetDoesNotExist_requiresCreateOnTheTarget() throws Exception {
        DocumentDocument source = doc("doc-1", "notes/analysis.md", "text/markdown");
        when(documentService.findById("doc-1")).thenReturn(Optional.of(source));
        when(documentService.readContent(source)).thenReturn("# Hello\n");
        when(documentService.findByPath(TENANT, PROJECT, "notes/analysis.pdf"))
                .thenReturn(Optional.empty());
        when(documentService.create(anyString(), anyString(), anyString(), any(), any(),
                anyString(), any(InputStream.class), any(), any()))
                .thenReturn(doc("doc-2", "notes/analysis.pdf", "application/pdf"));

        controller.exportPdf(TENANT, "doc-1", null, httpRequest);

        verify(authority).enforce(eq(httpRequest),
                eq(new Resource.Document(TENANT, PROJECT, "notes/analysis.md")),
                eq(Action.READ));
        verify(authority).enforce(eq(httpRequest),
                eq(new Resource.Document(TENANT, PROJECT, "notes/analysis.pdf")),
                eq(Action.CREATE));
    }

    @Test
    void exportPdf_targetExists_requiresWriteOnTheTarget() throws Exception {
        DocumentDocument source = doc("doc-1", "notes/analysis.md", "text/markdown");
        DocumentDocument existing = doc("doc-2", "notes/analysis.pdf", "application/pdf");
        when(documentService.findById("doc-1")).thenReturn(Optional.of(source));
        when(documentService.readContent(source)).thenReturn("# Hello\n");
        when(documentService.findByPath(TENANT, PROJECT, "notes/analysis.pdf"))
                .thenReturn(Optional.of(existing));
        when(documentService.replaceContent(anyString(), any(InputStream.class), anyString(),
                any(), any())).thenReturn(existing);

        controller.exportPdf(TENANT, "doc-1", null, httpRequest);

        // WRITE, not CREATE — the content of a document that already exists
        // is being replaced, which is the same distinction the copy path makes.
        verify(authority).enforce(eq(httpRequest),
                eq(new Resource.Document(TENANT, PROJECT, "notes/analysis.pdf")),
                eq(Action.WRITE));
    }

    /**
     * The finding: a reader could export, and thereby write, into a project
     * they may only read. The refusal has to land before the renderer runs —
     * otherwise a denied caller still costs a full PDF render.
     */
    @Test
    void exportPdf_writeDenied_refusesBeforeRendering() {
        DocumentDocument source = doc("doc-1", "notes/analysis.md", "text/markdown");
        when(documentService.findById("doc-1")).thenReturn(Optional.of(source));
        when(documentService.findByPath(TENANT, PROJECT, "notes/analysis.pdf"))
                .thenReturn(Optional.empty());
        doThrow(new PermissionDeniedException(
                SecurityContext.user("alice", TENANT, List.of()),
                new Resource.Document(TENANT, PROJECT, "notes/analysis.pdf"),
                Action.CREATE))
                .when(authority).enforce(eq(httpRequest),
                        eq(new Resource.Document(TENANT, PROJECT, "notes/analysis.pdf")),
                        eq(Action.CREATE));

        assertThatThrownBy(() -> controller.exportPdf(TENANT, "doc-1", null, httpRequest))
                .isInstanceOf(PermissionDeniedException.class);

        verify(markdownReportService, never()).render(anyString(), any());
        verify(documentService, never()).create(anyString(), anyString(), anyString(), any(),
                any(), anyString(), any(InputStream.class), any(), any());
    }

    @Test
    void exportPdf_passesTheCallerAsSubject_soCssRefsCanBeChecked() throws Exception {
        DocumentDocument source = doc("doc-1", "notes/analysis.md", "text/markdown");
        when(documentService.findById("doc-1")).thenReturn(Optional.of(source));
        when(documentService.readContent(source))
                .thenReturn("---\ncss: vance://other/x.css\n---\n# Hello\n");
        when(documentService.findByPath(TENANT, PROJECT, "notes/analysis.pdf"))
                .thenReturn(Optional.empty());
        when(documentService.create(anyString(), anyString(), anyString(), any(), any(),
                anyString(), any(InputStream.class), any(), any()))
                .thenReturn(doc("doc-2", "notes/analysis.pdf", "application/pdf"));

        controller.exportPdf(TENANT, "doc-1", null, httpRequest);

        ArgumentCaptor<MarkdownReportContext> captor =
                ArgumentCaptor.forClass(MarkdownReportContext.class);
        verify(markdownReportService).render(eq("pdf"), captor.capture());
        assertThat(captor.getValue().css()).isEqualTo("vance://other/x.css");
        assertThat(captor.getValue().subject()).isNotNull();
        assertThat(captor.getValue().subject().subjectId()).isEqualTo("alice");
    }

    private static DocumentDocument doc(String id, String path, String mime) {
        return DocumentDocument.builder()
                .id(id).tenantId(TENANT).projectId(PROJECT)
                .path(path).name(path.substring(path.lastIndexOf('/') + 1))
                .mimeType(mime)
                .build();
    }
}
