package de.mhus.vance.foot.connection.handlers;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.thinkprocess.TodoItem;
import de.mhus.vance.api.thinkprocess.TodoStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the deterministic render / dedup helpers of
 * {@link TodosUpdatedHandler}. The WS/terminal wiring is covered by the
 * Spring startup smoke test; here we only pin the pure logic.
 */
class TodosUpdatedHandlerTest {

    private static TodoItem todo(String content, TodoStatus status) {
        return TodoItem.builder().content(content).status(status).build();
    }

    @Test
    void renderLines_prefixesHeaderAndStatusGlyphs() {
        List<String> lines = TodosUpdatedHandler.renderLines("chat", List.of(
                todo("Read the file", TodoStatus.COMPLETED),
                todo("Edit the config", TodoStatus.IN_PROGRESS),
                todo("Run the build", TodoStatus.PENDING)));

        assertThat(lines).containsExactly(
                "Plan — chat",
                "✓ Read the file",
                "◐ Edit the config",
                "○ Run the build");
    }

    @Test
    void renderLines_fallsBackToActiveFormWhenContentBlank() {
        TodoItem item = TodoItem.builder()
                .content("")
                .activeForm("Reading the file")
                .status(TodoStatus.IN_PROGRESS)
                .build();

        List<String> lines = TodosUpdatedHandler.renderLines("worker", List.of(item));

        assertThat(lines).containsExactly("Plan — worker", "◐ Reading the file");
    }

    @Test
    void signature_ignoresIdentity_butReflectsStatusChange() {
        List<TodoItem> before = List.of(todo("Step one", TodoStatus.PENDING));
        List<TodoItem> afterSameStatus = List.of(todo("Step one", TodoStatus.PENDING));
        List<TodoItem> afterStatusChanged = List.of(todo("Step one", TodoStatus.COMPLETED));

        assertThat(TodosUpdatedHandler.signature(before))
                .isEqualTo(TodosUpdatedHandler.signature(afterSameStatus));
        assertThat(TodosUpdatedHandler.signature(before))
                .isNotEqualTo(TodosUpdatedHandler.signature(afterStatusChanged));
    }

    @Test
    void glyph_nullStatusFallsBackToPending() {
        assertThat(TodosUpdatedHandler.glyph(null)).isEqualTo("○");
        assertThat(TodosUpdatedHandler.glyph(TodoStatus.COMPLETED)).isEqualTo("✓");
    }
}
