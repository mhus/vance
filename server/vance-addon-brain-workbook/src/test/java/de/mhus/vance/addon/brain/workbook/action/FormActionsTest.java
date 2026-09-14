package de.mhus.vance.addon.brain.workbook.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.workpage.Block;
import de.mhus.vance.addon.brain.workpage.WorkPageDocument;
import de.mhus.vance.addon.brain.workpage.WorkPageService;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link FormResolveActionHandler} and
 * {@link FormResetActionHandler} against a mocked {@link WorkPageService} +
 * {@link DocumentService} — the handlers are thin transforms over the block
 * list, so the tests pin the grading/reset semantics and the
 * write-only-when-changed behaviour.
 */
class FormActionsTest {

    private static final String PAGE = "apps/g/quiz.workpage.md";

    private final DocumentService documentService = mock(DocumentService.class);
    private final WorkPageService workPageService = mock(WorkPageService.class);
    private final FormResolveActionHandler resolve = new FormResolveActionHandler(documentService, workPageService);
    private final FormResetActionHandler reset = new FormResetActionHandler(documentService, workPageService);

    @Test
    void resolve_gradesClosedTypes_skipsTextAndSolutionlessFields() {
        DocumentDocument doc = workpage("workpage");
        when(documentService.findByPath("t", "p", PAGE)).thenReturn(Optional.of(doc));
        when(workPageService.readDocument(doc))
                .thenReturn(new WorkPageDocument(
                        "Quiz",
                        null,
                        List.of(
                                field("q1", "choice", 0, 1, null, null), // wrong
                                field("q2", "multi", List.of(0, 1), List.of(1), null, null), // wrong
                                field("q3", "dropdown", 2, 2, null, null), // correct
                                field("q4", "text", "solution", "some answer", null, null), // not graded in v1
                                field("c1", "multi", null, List.of(0), null, null)))); // no solution → skipped

        ButtonActionResult r = resolve.run(ctx("form-resolve"));

        assertThat(r.message()).isEqualTo("1 of 3 answers correct");
        ArgumentCaptor<WorkPageDocument> saved = ArgumentCaptor.forClass(WorkPageDocument.class);
        verify(workPageService).writeDocument(org.mockito.ArgumentMatchers.eq(doc), saved.capture());
        List<Block> blocks = saved.getValue().blocks();
        assertThat(((Block.Field) blocks.get(0)).verdict()).isEqualTo("wrong");
        assertThat(((Block.Field) blocks.get(1)).verdict()).isEqualTo("wrong");
        assertThat(((Block.Field) blocks.get(2)).verdict()).isEqualTo("correct");
        assertThat(((Block.Field) blocks.get(3)).verdict()).isNull();
        assertThat(((Block.Field) blocks.get(4)).verdict()).isNull();
        // answers are kept
        assertThat(((Block.Field) blocks.get(0)).value()).isEqualTo(1);
    }

    @Test
    void resolve_staleFeedbackIsCleared_answersAndFieldsWithoutSolutionUntouched() {
        DocumentDocument doc = workpage("workpage");
        when(documentService.findByPath("t", "p", PAGE)).thenReturn(Optional.of(doc));
        when(workPageService.readDocument(doc))
                .thenReturn(new WorkPageDocument(
                        "Quiz",
                        null,
                        List.of(
                                // previously graded wrong with feedback; answer now correct
                                field("q1", "choice", 0, 0, "wrong", "old feedback"),
                                // previously graded — verdict unchanged → block not replaced, feedback kept
                                field("q2", "choice", 0, 1, "wrong", "still valid"),
                                field("c1", "text", null, "note", null, null))));

        ButtonActionResult r = resolve.run(ctx("form-resolve"));

        assertThat(r.message()).isEqualTo("1 of 2 answers correct");
        ArgumentCaptor<WorkPageDocument> saved = ArgumentCaptor.forClass(WorkPageDocument.class);
        verify(workPageService).writeDocument(org.mockito.ArgumentMatchers.eq(doc), saved.capture());
        List<Block> blocks = saved.getValue().blocks();
        assertThat(((Block.Field) blocks.get(0)).verdict()).isEqualTo("correct");
        assertThat(((Block.Field) blocks.get(0)).feedback()).isNull(); // stale → cleared
        assertThat(((Block.Field) blocks.get(1)).verdict()).isEqualTo("wrong");
        assertThat(((Block.Field) blocks.get(1)).feedback()).isEqualTo("still valid");
    }

    @Test
    void resolve_noCheckableFields_doesNotWrite() {
        DocumentDocument doc = workpage("workpage");
        when(documentService.findByPath("t", "p", PAGE)).thenReturn(Optional.of(doc));
        when(workPageService.readDocument(doc))
                .thenReturn(new WorkPageDocument(
                        "Checkliste",
                        null,
                        List.of(
                                field("c1", "multi", null, List.of(0), null, null),
                                field("c2", "text", null, "notizen", null, null))));

        ButtonActionResult r = resolve.run(ctx("form-resolve"));

        assertThat(r.message()).contains("Nothing to check");
        verify(workPageService, never()).writeDocument(any(), any());
    }

    @Test
    void reset_clearsMarkingsAndAnswers() {
        DocumentDocument doc = workpage("workpage");
        when(documentService.findByPath("t", "p", PAGE)).thenReturn(Optional.of(doc));
        when(workPageService.readDocument(doc))
                .thenReturn(new WorkPageDocument(
                        "Quiz",
                        null,
                        List.of(
                                field("q1", "choice", 0, 0, "correct", "well done"),
                                field("q2", "choice", 0, 1, null, "llm feedback only"),
                                field("c1", "text", null, "note", null, null))));

        ButtonActionResult r = reset.run(ctx("form-reset"));

        assertThat(r.message()).contains("Markings and answers cleared");
        ArgumentCaptor<WorkPageDocument> saved = ArgumentCaptor.forClass(WorkPageDocument.class);
        verify(workPageService).writeDocument(org.mockito.ArgumentMatchers.eq(doc), saved.capture());
        List<Block> blocks = saved.getValue().blocks();
        for (Block b : blocks) {
            Block.Field f = (Block.Field) b;
            assertThat(f.verdict()).isNull();
            assertThat(f.feedback()).isNull();
            assertThat(f.value()).isNull(); // answers cleared too — fresh run
        }
    }

    @Test
    void reset_alreadyClean_doesNotWrite() {
        DocumentDocument doc = workpage("workpage");
        when(documentService.findByPath("t", "p", PAGE)).thenReturn(Optional.of(doc));
        when(workPageService.readDocument(doc))
                .thenReturn(new WorkPageDocument(
                        "Quiz",
                        null,
                        List.of(
                                field("q1", "choice", 0, null, null, null),
                                field("c1", "text", null, null, null, null))));

        ButtonActionResult r = reset.run(ctx("form-reset"));

        assertThat(r.message()).contains("Nothing to clear");
        verify(workPageService, never()).writeDocument(any(), any());
    }

    @Test
    void resolve_nonWorkpageDoc_failsClosed() {
        DocumentDocument doc = workpage("text");
        when(documentService.findByPath("t", "p", PAGE)).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> resolve.run(ctx("form-resolve")))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not a workpage");
        verify(workPageService, never()).writeDocument(any(), any());
    }

    @Test
    void resolve_missingDoc_failsClosed() {
        when(documentService.findByPath("t", "p", PAGE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolve.run(ctx("form-resolve")))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("workpage not found");
    }

    // ---- helpers -------------------------------------------------------

    private static ButtonActionContext ctx(String type) {
        return new ButtonActionContext("t", "p", "user", PAGE, Map.of("type", type));
    }

    private static Block.Field field(
            String id, String type, Object solution, Object value, String verdict, String feedback) {
        return new Block.Field(id, type, "Frage?", List.of("a", "b", "c"), solution, value, verdict, feedback);
    }

    private DocumentDocument workpage(String kind) {
        DocumentDocument doc = mock(DocumentDocument.class);
        when(doc.getKind()).thenReturn(kind);
        return doc;
    }
}
