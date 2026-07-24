package de.mhus.vance.addon.brain.kanban;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.kind.CardDocument;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Deterministic-core coverage for the Kanban addon: the leaf-folder column
 * rule ({@link KanbanFolderReader#columnFor}) and the card-patch merge
 * contract ({@link KanbanBoardController#mergePatch} — "blank string clears,
 * null leaves untouched"). Both are pure and drive the board UX; a
 * regression would silently mis-route cards or lose/keep fields wrongly.
 */
class KanbanDeterministicCoreTest {

    // ── columnFor ────────────────────────────────────────────────────────

    @Test
    void columnFor_usesImmediateParentFolderAsColumn() {
        assertThat(KanbanFolderReader.columnFor("board", "board/doing/card-1.md"))
                .isEqualTo("doing");
    }

    @Test
    void columnFor_nestedPath_usesLeafFolderNotFullPath() {
        assertThat(KanbanFolderReader.columnFor("board", "board/done/2026/card-9.md"))
                .isEqualTo("2026");
    }

    @Test
    void columnFor_fileDirectlyInSuiteRoot_isDefaultColumn() {
        assertThat(KanbanFolderReader.columnFor("board", "board/loose.md"))
                .isEqualTo(KanbanFolderReader.DEFAULT_COLUMN);
    }

    // ── mergePatch ───────────────────────────────────────────────────────

    private static CardDocument existing() {
        return new CardDocument("card", "Old", "high", "alice",
                List.of("a", "b"), "2026-01-01", 3.0, true, "old body",
                new java.util.LinkedHashMap<>());
    }

    @Test
    void mergePatch_nullFields_leaveExistingUntouched() {
        KanbanCardUpdateRequest empty = new KanbanCardUpdateRequest(); // all null

        CardDocument merged = KanbanBoardController.mergePatch(existing(), empty);

        assertThat(merged.title()).isEqualTo("Old");
        assertThat(merged.priority()).isEqualTo("high");
        assertThat(merged.assignee()).isEqualTo("alice");
        assertThat(merged.labels()).containsExactly("a", "b");
        assertThat(merged.dueDate()).isEqualTo("2026-01-01");
        assertThat(merged.estimate()).isEqualTo(3.0);
        assertThat(merged.blocked()).isTrue();
        assertThat(merged.body()).isEqualTo("old body");
    }

    @Test
    void mergePatch_blankStringClearsNullableField() {
        KanbanCardUpdateRequest p = new KanbanCardUpdateRequest();
        p.setPriority("");   // blank → clear
        p.setAssignee("");   // blank → clear
        p.setDueDate("");    // blank → clear

        CardDocument merged = KanbanBoardController.mergePatch(existing(), p);

        assertThat(merged.priority()).isNull();
        assertThat(merged.assignee()).isNull();
        assertThat(merged.dueDate()).isNull();
    }

    @Test
    void mergePatch_nonBlankValues_override() {
        KanbanCardUpdateRequest p = new KanbanCardUpdateRequest();
        p.setTitle("New");
        p.setPriority("low");
        p.setLabels(List.of("x"));
        p.setEstimate(1.0);
        p.setBlocked(false);
        p.setBody("new body");

        CardDocument merged = KanbanBoardController.mergePatch(existing(), p);

        assertThat(merged.title()).isEqualTo("New");
        assertThat(merged.priority()).isEqualTo("low");
        assertThat(merged.labels()).containsExactly("x");
        assertThat(merged.estimate()).isEqualTo(1.0);
        assertThat(merged.blocked()).isFalse();
        assertThat(merged.body()).isEqualTo("new body");
    }
}
