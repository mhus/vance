package de.mhus.vance.addon.brain.designer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Pure tests for the content route's URI-tail parsing — the piece that
 * decides which file a sandboxed iframe URL addresses — plus the
 * {@code view} endpoint's project-READ enforcement. No Spring context:
 * the mapping logic is static, the enforcement check is plain Mockito.
 */
class DesignerContentControllerTest {

    @Test
    void innerPathAfter_extractsTailAfterDesignSegment() {
        assertThat(DesignerContentController.innerPathAfter(
                        "/brain/acme/addon/designer/content/doc1/token/landing/assets/main.css"))
                .isEqualTo("assets/main.css");
    }

    @Test
    void innerPathAfter_emptyTailForDesignRoot() {
        assertThat(DesignerContentController.innerPathAfter("/brain/acme/addon/designer/content/doc1/token/landing"))
                .isEqualTo("");
        assertThat(DesignerContentController.innerPathAfter("/brain/acme/addon/designer/content/doc1/token/landing/"))
                .isEqualTo("");
    }

    @Test
    void innerPathAfter_percentDecodesPerSegment() {
        assertThat(DesignerContentController.innerPathAfter(
                        "/brain/acme/addon/designer/content/doc1/token/landing/my%20file.css"))
                .isEqualTo("my file.css");
        // A literal plus stays a plus — this is a path, not a form body.
        assertThat(DesignerContentController.innerPathAfter(
                        "/brain/acme/addon/designer/content/doc1/token/landing/a+b.css"))
                .isEqualTo("a+b.css");
    }

    @Test
    void routePattern_matchesDesignRootAndNestedFiles() {
        // The iframe loads the design by its folder URL (trailing slash,
        // so relative sub-resources resolve inside it); every file below
        // the design must match too. This pins the URL contract against
        // a PathPatternParser behaviour change.
        var parser = new org.springframework.web.util.pattern.PathPatternParser();
        var pattern = parser.parse("/brain/{tenant}/addon/designer/content/{appDocId}/{token}/{design}/**");
        for (String uri : new String[] {
            "/brain/acme/addon/designer/content/d1/t/landing",
            "/brain/acme/addon/designer/content/d1/t/landing/",
            "/brain/acme/addon/designer/content/d1/t/landing/style.css",
            "/brain/acme/addon/designer/content/d1/t/landing/assets/a/b.css",
        }) {
            assertThat(pattern.matches(org.springframework.http.server.PathContainer.parsePath(uri)))
                    .as(uri)
                    .isTrue();
        }
    }

    @Test
    void servedMime_correctsHtmlAndCssFromTheExtension() {
        // The historical bug: doc_write's kind default stored text/markdown
        // on an index.html — nosniff turned the preview into raw source.
        // Serving time derives the two preview-critical types from the
        // extension, whatever the row says.
        assertThat(DesignerContentController.servedMime("designs/landing/index.html", docWithMime("text/markdown")))
                .isEqualTo("text/html");
        assertThat(DesignerContentController.servedMime("designs/landing/style.css", docWithMime("text/markdown")))
                .isEqualTo("text/css");
        assertThat(DesignerContentController.servedMime("designs/landing/INDEX.HTML", docWithMime("text/plain")))
                .isEqualTo("text/html");
    }

    @Test
    void servedMime_leavesEveryOtherFileOnItsStoredMime() {
        // Only html and css are corrected — an image or font with a wrong
        // row mime is the validator's finding, not something the route
        // should guess over.
        assertThat(DesignerContentController.servedMime(
                        "designs/landing/assets/logo.png", docWithMime("text/markdown")))
                .isEqualTo("text/markdown");
        assertThat(DesignerContentController.servedMime("designs/landing/script.js", docWithMime("text/html")))
                .isEqualTo("text/html");
    }

    private static de.mhus.vance.shared.document.DocumentDocument docWithMime(String mime) {
        de.mhus.vance.shared.document.DocumentDocument d = new de.mhus.vance.shared.document.DocumentDocument();
        d.setMimeType(mime);
        return d;
    }

    @Test
    void innerPathAfter_toleratesForeignUri() {
        // No content segment at all — treat as "entry file", the route
        // then fails on token validation anyway.
        assertThat(DesignerContentController.innerPathAfter("/brain/acme/documents"))
                .isEqualTo("");
    }

    @Test
    void view_enforcesProjectRead() {
        // The catalogue names designs, titles and file lists of the project
        // folder — project content, so the same project READ applies as
        // for the design-skill listing. Pins that an authenticated
        // tenant user cannot enumerate a foreign project's designer app.
        DesignerFolderReader folderReader = Mockito.mock(DesignerFolderReader.class);
        RequestAuthority authority = Mockito.mock(RequestAuthority.class);
        DocumentService documentService = Mockito.mock(DocumentService.class);
        HttpServletRequest httpRequest = Mockito.mock(HttpServletRequest.class);
        var controller = new DesignerContentController(
                folderReader,
                Mockito.mock(DesignerPreviewTokenService.class),
                documentService,
                authority,
                Mockito.mock(PermissionService.class),
                Mockito.mock(SecurityContextFactory.class),
                Mockito.mock(DesignerApplication.class),
                Mockito.mock(DesignerSkillService.class),
                Mockito.mock(ThinkProcessService.class));
        when(folderReader.scan("acme", "p1", "myapp")).thenReturn(new DesignerFolderReader.Scan(List.of()));

        controller.view("acme", "p1", "myapp", httpRequest);

        verify(authority).enforce(httpRequest, new Resource.Project("acme", "p1"), Action.READ);
    }
}
