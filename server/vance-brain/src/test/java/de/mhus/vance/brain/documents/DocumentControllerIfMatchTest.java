package de.mhus.vance.brain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.SecurityContext;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * Conditional writes on the content endpoint.
 *
 * <p>Without {@code If-Match} the last writer wins silently: two readers who
 * both opened a document and both save leave the second body in place, and the
 * first change is gone without a word. These tests pin the three answers that
 * matter — unchanged writes, changed refuses, absent behaves as before — plus
 * the two shapes where a check would *look* like protection without being one.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentControllerIfMatchTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "proj";

    @Mock private DocumentService documentService;
    @Mock private RequestAuthority authority;
    @Mock private HttpServletRequest httpRequest;

    private DocumentController controller;

    @BeforeEach
    void setUp() throws IOException {
        controller = new DocumentController(documentService, authority);
        when(httpRequest.getAttribute(AccessFilterBase.ATTR_USERNAME)).thenReturn("alice");
        when(authority.contextOf(httpRequest))
                .thenReturn(SecurityContext.user("alice", TENANT, List.of()));
        when(httpRequest.getInputStream()).thenReturn(body("new body"));
    }

    @Test
    void matchingVersion_writes() throws IOException {
        DocumentDocument before = doc("doc-1", "apps/a/rows/1.yaml", "store-1");
        DocumentDocument after = doc("doc-1", "apps/a/rows/1.yaml", "store-2");
        when(documentService.findById("doc-1")).thenReturn(Optional.of(before));
        when(documentService.replaceContent(any(), any(), any(), any(), any())).thenReturn(after);

        ResponseEntity<?> response = controller.replaceContent(
                TENANT, "doc-1", "text/yaml", "\"store-1\"", null, httpRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(documentService).replaceContent(any(), any(), any(), any(), any());
    }

    /**
     * The whole point: somebody wrote between the read and this write. Nothing
     * is written, and the caller is told rather than left believing it saved.
     */
    @Test
    void staleVersion_refusesAndWritesNothing() {
        DocumentDocument current = doc("doc-1", "apps/a/rows/1.yaml", "store-2");
        when(documentService.findById("doc-1")).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> controller.replaceContent(
                TENANT, "doc-1", "text/yaml", "\"store-1\"", null, httpRequest))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("apps/a/rows/1.yaml")
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_FAILED);

        verifyNoWrite();
    }

    /** Optional: every caller that never heard of the header keeps working. */
    @Test
    void withoutTheHeader_writesUnconditionally() throws IOException {
        DocumentDocument current = doc("doc-1", "apps/a/rows/1.yaml", "store-2");
        when(documentService.findById("doc-1")).thenReturn(Optional.of(current));
        when(documentService.replaceContent(any(), any(), any(), any(), any()))
                .thenReturn(current);

        controller.replaceContent(TENANT, "doc-1", "text/yaml", null, null, httpRequest);

        verify(documentService).replaceContent(any(), any(), any(), any(), any());
    }

    /** `*` means "any existing representation", and the document exists. */
    @Test
    void star_matchesAnyExistingDocument() throws IOException {
        DocumentDocument current = doc("doc-1", "apps/a/rows/1.yaml", "store-9");
        when(documentService.findById("doc-1")).thenReturn(Optional.of(current));
        when(documentService.replaceContent(any(), any(), any(), any(), any()))
                .thenReturn(current);

        controller.replaceContent(TENANT, "doc-1", "text/yaml", "*", null, httpRequest);

        verify(documentService).replaceContent(any(), any(), any(), any(), any());
    }

    /**
     * The response carries the **new** version.
     *
     * <p>A caller doing read-modify-write in a loop would otherwise have to
     * re-read the document after every save purely to learn what to send next
     * time — and a caller that skipped that step would be refused on its own
     * second write.
     */
    @Test
    void response_carriesTheNewVersion() throws IOException {
        DocumentDocument before = doc("doc-1", "apps/a/rows/1.yaml", "store-1");
        DocumentDocument after = doc("doc-1", "apps/a/rows/1.yaml", "store-2");
        when(documentService.findById("doc-1")).thenReturn(Optional.of(before));
        when(documentService.replaceContent(any(), any(), any(), any(), any())).thenReturn(after);

        ResponseEntity<?> response = controller.replaceContent(
                TENANT, "doc-1", "text/yaml", "\"store-1\"", null, httpRequest);

        assertThat(response.getHeaders().getFirst(HttpHeaders.ETAG)).isEqualTo("\"store-2\"");
    }

    /**
     * A mounted path carries no version, so the fallback would compare against
     * the immutable document id and match every time. A check that cannot fail
     * is worse than none, because it reads like protection — so it is skipped,
     * and writes there stay the document lock's business.
     */
    @Test
    void mountedPath_isNotCheckedAgainstItsImmutableId() throws IOException {
        DocumentDocument mounted = doc("ext_abc", "_ext/demo/analysis.yaml", null);
        when(documentService.findById("ext_abc")).thenReturn(Optional.of(mounted));
        when(documentService.replaceContent(any(), any(), any(), any(), any()))
                .thenReturn(mounted);

        ResponseEntity<?> response = controller.replaceContent(
                TENANT, "ext_abc", "text/yaml", "\"whatever\"", null, httpRequest);

        assertThat(response.getHeaders().getFirst(HttpHeaders.ETAG)).isNull();
        verify(documentService).replaceContent(any(), any(), any(), any(), any());
    }

    /**
     * A document without a storageId falls back to its id, which never changes
     * — so a conditional write against it always matches and offers no
     * protection. Pinned so the shape is a known limit rather than a surprise.
     */
    @Test
    void documentWithoutStorageId_matchesOnItsId() throws IOException {
        DocumentDocument legacy = doc("doc-old", "notes/legacy.md", null);
        when(documentService.findById("doc-old")).thenReturn(Optional.of(legacy));
        when(documentService.replaceContent(any(), any(), any(), any(), any()))
                .thenReturn(legacy);

        controller.replaceContent(TENANT, "doc-old", "text/markdown", "\"doc-old\"",
                null, httpRequest);

        verify(documentService).replaceContent(any(), any(), any(), any(), any());
    }

    private void verifyNoWrite() {
        verify(documentService, never()).replaceContent(any(), any(), any(), any(), any());
    }

    private static DocumentDocument doc(String id, String path, String storageId) {
        return DocumentDocument.builder()
                .id(id).tenantId(TENANT).projectId(PROJECT)
                .path(path).name(path.substring(path.lastIndexOf('/') + 1))
                .mimeType("text/plain").storageId(storageId)
                .build();
    }

    private static ServletInputStream body(String text) {
        ByteArrayInputStream in = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        return new ServletInputStream() {
            @Override public int read() {
                return in.read();
            }
            @Override public boolean isFinished() {
                return in.available() == 0;
            }
            @Override public boolean isReady() {
                return true;
            }
            @Override public void setReadListener(jakarta.servlet.ReadListener listener) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
