package de.mhus.vance.brain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.tools.report.MarkdownReportService;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * What the content endpoint does with a query string.
 *
 * <p>Worth its own test because the mistake it guards has now been made twice
 * in two places: take the query off the request, decide it is not applicable,
 * and answer 200 with the plain document. That reads as success at every layer
 * — the caller asked for a view of something and receives something.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentControllerContentQueryTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "proj";

    @Mock private DocumentService documentService;
    @Mock private RequestAuthority authority;
    @Mock private MarkdownReportService markdownReportService;
    @Mock private HttpServletRequest httpRequest;

    private DocumentController controller;

    @BeforeEach
    void setUp() {
        controller = new DocumentController(documentService, authority, markdownReportService);
        when(httpRequest.getAttribute(AccessFilterBase.ATTR_USERNAME)).thenReturn("alice");
        when(authority.contextOf(httpRequest))
                .thenReturn(SecurityContext.user("alice", TENANT, List.of()));
    }

    @Test
    void mountedDocument_withQuery_forwardsItToTheSource() {
        DocumentDocument doc = doc("ext_abc", "_ext/demo/analysis.yaml");
        when(documentService.findById("ext_abc")).thenReturn(Optional.of(doc));
        when(httpRequest.getQueryString()).thenReturn("from=2026-02-01&to=2026-03-31");
        when(documentService.loadContent(doc, "from=2026-02-01&to=2026-03-31"))
                .thenReturn(stream("computed:"));

        controller.content(TENANT, "ext_abc", false, null, httpRequest);

        verify(documentService).loadContent(doc, "from=2026-02-01&to=2026-03-31");
    }

    @Test
    void mountedDocument_withOnlyReservedParameters_readsPlainly() {
        // download= is ours and always legitimately present on a download
        // link. Treating it as a parameterised read would have a query-less
        // mount refuse an ordinary download.
        DocumentDocument doc = doc("ext_abc", "_ext/demo/analysis.yaml");
        when(documentService.findById("ext_abc")).thenReturn(Optional.of(doc));
        when(httpRequest.getQueryString()).thenReturn("download=true");
        when(documentService.loadContent(eq(doc), isNull())).thenReturn(stream("plain"));

        controller.content(TENANT, "ext_abc", true, null, httpRequest);

        verify(documentService).loadContent(doc, null);
    }

    @Test
    void storedDocument_withQuery_is400AndNotAPlainRead() {
        // The regression this test exists for: the endpoint used to pass null
        // for a non-mounted document, so the query vanished and the caller got
        // the ordinary file with a 200.
        DocumentDocument doc = doc("doc-1", "apps/thing/main.js");
        when(documentService.findById("doc-1")).thenReturn(Optional.of(doc));
        when(httpRequest.getQueryString()).thenReturn("from=2026-02-01");

        assertThatThrownBy(() ->
                controller.content(TENANT, "doc-1", false, null, httpRequest))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("apps/thing/main.js");

        verify(documentService, never()).loadContent(eq(doc), isNull());
    }

    @Test
    void storedDocument_withoutQuery_isUnaffected() {
        DocumentDocument doc = doc("doc-1", "apps/thing/main.js");
        when(documentService.findById("doc-1")).thenReturn(Optional.of(doc));
        when(httpRequest.getQueryString()).thenReturn(null);
        when(documentService.loadContent(eq(doc), isNull())).thenReturn(stream("body"));

        controller.content(TENANT, "doc-1", false, null, httpRequest);

        verify(documentService).loadContent(doc, null);
    }

    private static DocumentDocument doc(String id, String path) {
        return DocumentDocument.builder()
                .id(id).tenantId(TENANT).projectId(PROJECT)
                .path(path).name(path.substring(path.lastIndexOf('/') + 1))
                .mimeType("text/plain")
                .build();
    }

    private static ByteArrayInputStream stream(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }
}
