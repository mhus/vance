package de.mhus.vance.brain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.documents.DocumentDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for the single-document duplicate endpoint
 * ({@link DocumentController#duplicate}).
 *
 * <p>The behaviour worth pinning down is the name search: the copy lands in the
 * source's folder under the first free {@code copy <n>}, the suffix goes before
 * the extension, and duplicating a duplicate counts up instead of nesting.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentControllerDuplicateTest {

    @Mock private DocumentService documentService;
    @Mock private RequestAuthority authority;
    @Mock private HttpServletRequest httpRequest;

    private DocumentController controller;

    private static final String TENANT = "acme";
    private static final String PROJECT = "proj-a";
    private static final String USERNAME = "alice";

    @BeforeEach
    void setUp() {
        controller = new DocumentController(documentService, authority);
        when(httpRequest.getAttribute(AccessFilterBase.ATTR_USERNAME)).thenReturn(USERNAME);
        when(authority.contextOf(httpRequest))
                .thenReturn(SecurityContext.user(USERNAME, TENANT, List.of()));
    }

    private DocumentDocument doc(String id, String path, @org.jspecify.annotations.Nullable String title) {
        return DocumentDocument.builder()
                .id(id)
                .tenantId(TENANT)
                .projectId(PROJECT)
                .path(path)
                .name(path.substring(path.lastIndexOf('/') + 1))
                .title(title)
                .tags(List.of("tag1"))
                .mimeType("text/markdown")
                .autoSummary(false)
                .build();
    }

    private void stubSource(DocumentDocument source) {
        when(documentService.findById(source.getId())).thenReturn(Optional.of(source));
        when(documentService.loadContent(source))
                .thenReturn(new ByteArrayInputStream("# Hello".getBytes(StandardCharsets.UTF_8)));
        // Nothing exists in the target folder unless a test says otherwise.
        when(documentService.findByPath(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(documentService.create(
                        anyString(), anyString(), anyString(),
                        any(), any(), any(), any(InputStream.class), any(),
                        any(), any(), any()))
                .thenAnswer(inv -> doc("d2", inv.getArgument(2), inv.getArgument(3)));
    }

    @Test
    void duplicate_freeName_copiesIntoSameFolderWithCopyOne() {
        DocumentDocument source = doc("d1", "notes/ch1.md", "Chapter 1");
        stubSource(source);

        ResponseEntity<DocumentDto> res = controller.duplicate(TENANT, "d1", PROJECT, httpRequest);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(documentService).create(
                eq(TENANT), eq(PROJECT), eq("notes/ch1 copy 1.md"),
                eq("Chapter 1 copy 1"), eq(List.of("tag1")), eq("text/markdown"),
                any(InputStream.class), eq(USERNAME),
                eq(Boolean.FALSE), eq(null), any());
    }

    @Test
    void duplicate_takenNames_picksNextFreeIndex() {
        DocumentDocument source = doc("d1", "notes/ch1.md", null);
        stubSource(source);
        when(documentService.findByPath(TENANT, PROJECT, "notes/ch1 copy 1.md"))
                .thenReturn(Optional.of(doc("x1", "notes/ch1 copy 1.md", null)));
        when(documentService.findByPath(TENANT, PROJECT, "notes/ch1 copy 2.md"))
                .thenReturn(Optional.of(doc("x2", "notes/ch1 copy 2.md", null)));

        controller.duplicate(TENANT, "d1", PROJECT, httpRequest);

        verify(documentService).create(
                eq(TENANT), eq(PROJECT), eq("notes/ch1 copy 3.md"),
                eq(null), any(), any(), any(InputStream.class), any(),
                any(), any(), any());
    }

    @Test
    void duplicate_ofADuplicate_countsUpInsteadOfNesting() {
        DocumentDocument source = doc("d1", "notes/ch1 copy 1.md", "Chapter 1 copy 1");
        stubSource(source);
        when(documentService.findByPath(TENANT, PROJECT, "notes/ch1 copy 1.md"))
                .thenReturn(Optional.of(source));

        controller.duplicate(TENANT, "d1", PROJECT, httpRequest);

        verify(documentService).create(
                eq(TENANT), eq(PROJECT), eq("notes/ch1 copy 2.md"),
                eq("Chapter 1 copy 2"), any(), any(), any(InputStream.class), any(),
                any(), any(), any());
    }

    @Test
    void duplicate_dotFile_appendsSuffixWithoutInventingAnExtension() {
        DocumentDocument source = doc("d1", ".gitignore", null);
        stubSource(source);

        controller.duplicate(TENANT, "d1", PROJECT, httpRequest);

        verify(documentService).create(
                eq(TENANT), eq(PROJECT), eq(".gitignore copy 1"),
                any(), any(), any(), any(InputStream.class), any(),
                any(), any(), any());
    }

    @Test
    void duplicate_rootLevelDocument_staysAtRoot() {
        DocumentDocument source = doc("d1", "readme.md", null);
        stubSource(source);

        controller.duplicate(TENANT, "d1", PROJECT, httpRequest);

        verify(documentService).create(
                eq(TENANT), eq(PROJECT), eq("readme copy 1.md"),
                any(), any(), any(), any(InputStream.class), any(),
                any(), any(), any());
    }

    @Test
    void duplicate_foreignProject_isNotFound() {
        DocumentDocument source = doc("d1", "notes/ch1.md", null);
        when(documentService.findById("d1")).thenReturn(Optional.of(source));

        assertThatThrownBy(() -> controller.duplicate(TENANT, "d1", "other-project", httpRequest))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        verify(documentService, never()).create(
                anyString(), anyString(), anyString(),
                any(), any(), any(), any(InputStream.class), any(),
                any(), any(), any());
    }

    @Test
    void duplicate_noReadPermission_isRejected() {
        DocumentDocument source = doc("d1", "notes/ch1.md", null);
        when(documentService.findById("d1")).thenReturn(Optional.of(source));
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(authority).enforce(eq(httpRequest), any(Resource.Document.class), eq(Action.READ));

        assertThatThrownBy(() -> controller.duplicate(TENANT, "d1", PROJECT, httpRequest))
                .isInstanceOf(ResponseStatusException.class);
        verify(documentService, never()).create(
                anyString(), anyString(), anyString(),
                any(), any(), any(), any(InputStream.class), any(),
                any(), any(), any());
    }

    @Test
    void duplicate_lostRaceOnTheFreeName_isConflict() {
        DocumentDocument source = doc("d1", "notes/ch1.md", null);
        stubSource(source);
        when(documentService.create(
                        anyString(), anyString(), anyString(),
                        any(), any(), any(), any(InputStream.class), any(),
                        any(), any(), any()))
                .thenThrow(new DocumentService.DocumentAlreadyExistsException("exists"));

        assertThatThrownBy(() -> controller.duplicate(TENANT, "d1", PROJECT, httpRequest))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }
}
