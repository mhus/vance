package de.mhus.vance.addon.brain.workpage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WorkPageService#requireByPath} — the shared
 * fail-closed lookup of the button action handlers: absent document and
 * non-workpage kind both throw with the path in the message.
 */
class WorkPageServiceRequireByPathTest {

    private final DocumentService documentService = mock(DocumentService.class);
    private final WorkPageService service = new WorkPageService(
            documentService,
            mock(WorkPageParser.class),
            mock(WorkPageSerializer.class),
            mock(de.mhus.vance.brain.permission.SecurityContextFactory.class));

    @Test
    void requireByPath_workpageDoc_returnsIt() {
        DocumentDocument doc = doc("workpage");
        when(documentService.findByPath("t", "p", "apps/g/quiz.workpage.md")).thenReturn(Optional.of(doc));

        assertThat(service.requireByPath("t", "p", "apps/g/quiz.workpage.md")).isSameAs(doc);
    }

    @Test
    void requireByPath_missingDoc_failsClosedWithPath() {
        when(documentService.findByPath("t", "p", "apps/g/missing.workpage.md")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireByPath("t", "p", "apps/g/missing.workpage.md"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("workpage not found")
                .hasMessageContaining("apps/g/missing.workpage.md");
    }

    @Test
    void requireByPath_nonWorkpageKind_failsClosedWithKind() {
        DocumentDocument doc = doc("text");
        when(documentService.findByPath("t", "p", "notes.md")).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> service.requireByPath("t", "p", "notes.md"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not a workpage")
                .hasMessageContaining("kind=text");
    }

    private DocumentDocument doc(String kind) {
        DocumentDocument doc = mock(DocumentDocument.class);
        when(doc.getKind()).thenReturn(kind);
        return doc;
    }
}
