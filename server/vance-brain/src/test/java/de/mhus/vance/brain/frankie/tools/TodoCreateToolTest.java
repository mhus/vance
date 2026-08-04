package de.mhus.vance.brain.frankie.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.TodoItem;
import de.mhus.vance.api.thinkprocess.TodoStatus;
import de.mhus.vance.brain.arthur.PlanModeEventEmitter;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TodoCreateToolTest {

    private static final String PROC_ID = "proc-1";

    private ThinkProcessService thinkProcessService;
    private PlanModeEventEmitter emitter;
    private TodoCreateTool tool;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        emitter = mock(PlanModeEventEmitter.class);
        tool = new TodoCreateTool(thinkProcessService, emitter);
    }

    @Test
    void create_emptyList_assignsServerIdsStartingAt1() {
        // Pre-state: no todos.
        ThinkProcessDocument empty = new ThinkProcessDocument();
        empty.setId(PROC_ID);
        when(thinkProcessService.findById(PROC_ID))
                .thenReturn(Optional.of(empty))
                .thenReturn(Optional.of(withTodos(
                        TodoItem.builder().id("1").status(TodoStatus.PENDING)
                                .content("a").build(),
                        TodoItem.builder().id("2").status(TodoStatus.PENDING)
                                .content("b").build())));
        when(thinkProcessService.addTodos(eq(PROC_ID), any())).thenReturn(List.of(
                TodoItem.builder().id("1").status(TodoStatus.PENDING).content("a").build(),
                TodoItem.builder().id("2").status(TodoStatus.PENDING).content("b").build()));

        Map<String, Object> out = tool.invoke(Map.of(
                "items", List.of(
                        Map.of("content", "a"),
                        Map.of("content", "b"))),
                ctxFor(PROC_ID));

        assertThat(out).containsEntry("ok", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> created = (List<Map<String, Object>>) out.get("created");
        assertThat(created).hasSize(2);
        assertThat(created.get(0)).containsEntry("id", "1").containsEntry("content", "a");
        assertThat(created.get(1)).containsEntry("id", "2").containsEntry("content", "b");

        // Only todos-updated — never plan-proposed. That frame carries
        // Arthur/Eddie approval semantics (Foot renders an "answer ok to
        // approve" banner on it) which is wrong for Frankie's TodoList.
        verify(emitter).emitTodosUpdated(any(), any());
        verify(emitter, never()).emitPlanProposed(any(), any(), any(Integer.class));
    }

    @Test
    void create_intoNonEmptyList_emitsOnlyTodosUpdated() {
        ThinkProcessDocument existing = withTodos(
                TodoItem.builder().id("1").status(TodoStatus.PENDING).content("a").build());
        when(thinkProcessService.findById(PROC_ID))
                .thenReturn(Optional.of(existing))
                .thenReturn(Optional.of(withTodos(
                        TodoItem.builder().id("1").status(TodoStatus.PENDING).content("a").build(),
                        TodoItem.builder().id("2").status(TodoStatus.PENDING).content("b").build())));
        when(thinkProcessService.addTodos(eq(PROC_ID), any())).thenReturn(List.of(
                TodoItem.builder().id("2").status(TodoStatus.PENDING).content("b").build()));

        tool.invoke(Map.of("items", List.of(Map.of("content", "b"))), ctxFor(PROC_ID));

        verify(emitter).emitTodosUpdated(any(), any());
        verify(emitter, never()).emitPlanProposed(any(), any(), any(Integer.class));
    }

    @Test
    void create_skipsItemsWithoutContent() {
        when(thinkProcessService.findById(PROC_ID))
                .thenReturn(Optional.of(new ThinkProcessDocument()));
        when(thinkProcessService.addTodos(eq(PROC_ID), any())).thenReturn(List.of(
                TodoItem.builder().id("1").status(TodoStatus.PENDING).content("good").build()));

        tool.invoke(Map.of("items", List.of(
                        Map.of("content", "good"),
                        Map.of("content", ""),       // blank
                        Map.of("activeForm", "no content"),  // missing content
                        "not a map")),               // wrong shape
                ctxFor(PROC_ID));

        ArgumentCaptor<List<TodoItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(thinkProcessService).addTodos(eq(PROC_ID), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getContent()).isEqualTo("good");
    }

    @Test
    void create_zeroParseableItems_skipsServiceCall() {
        Map<String, Object> out = tool.invoke(Map.of(
                "items", List.of(Map.of("activeForm", "no content"))),
                ctxFor(PROC_ID));

        assertThat(out).containsEntry("ok", true);
        assertThat(out).containsKey("note");
        verify(thinkProcessService, never()).addTodos(any(), any());
    }

    @Test
    void create_serviceReturnsNull_throws() {
        when(thinkProcessService.findById(PROC_ID))
                .thenReturn(Optional.of(new ThinkProcessDocument()));
        when(thinkProcessService.addTodos(eq(PROC_ID), any())).thenReturn(null);

        assertThatThrownBy(() ->
                tool.invoke(Map.of("items", List.of(Map.of("content", "x"))),
                        ctxFor(PROC_ID)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void requiresProcessScope() {
        ToolInvocationContext noProc = new ToolInvocationContext(
                "t", "p", "s", null, "u");

        assertThatThrownBy(() ->
                tool.invoke(Map.of("items", List.of(Map.of("content", "x"))), noProc))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("process scope");
    }

    @Test
    void rejectsMissingItems() {
        assertThatThrownBy(() -> tool.invoke(Map.of(), ctxFor(PROC_ID)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("items");
    }

    @Test
    void dedup_existingContent_isSkippedNotAppended() {
        when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.of(withTodos(
                TodoItem.builder().id("1").status(TodoStatus.PENDING)
                        .content("Add toAnsi() helper").build())));

        Map<String, Object> out = tool.invoke(
                Map.of("items", List.of(Map.of("content", "Add toAnsi() helper"))),
                ctxFor(PROC_ID));

        assertThat(out).containsEntry("created", List.of());
        assertThat(out).containsEntry("skipped", 1);
        verify(thinkProcessService, never()).addTodos(any(), any());
    }

    @Test
    void dedup_wholePlanResend_appendsNothing() {
        // The TodoWrite habit: model re-sends the whole plan (incl. a
        // COMPLETED item) every update. Dedup must match all statuses so
        // nothing is re-appended.
        when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.of(withTodos(
                TodoItem.builder().id("1").status(TodoStatus.PENDING)
                        .content("Refactor StatusBar").build(),
                TodoItem.builder().id("2").status(TodoStatus.COMPLETED)
                        .content("Write tests").build())));

        Map<String, Object> out = tool.invoke(
                Map.of("items", List.of(
                        Map.of("content", "Refactor StatusBar"),
                        Map.of("content", "Write tests"))),
                ctxFor(PROC_ID));

        assertThat(out).containsEntry("skipped", 2);
        verify(thinkProcessService, never()).addTodos(any(), any());
    }

    private static ThinkProcessDocument withTodos(TodoItem... items) {
        ThinkProcessDocument doc = new ThinkProcessDocument();
        doc.setId(PROC_ID);
        List<TodoItem> list = new ArrayList<>();
        for (TodoItem t : items) list.add(t);
        doc.setTodos(list);
        return doc;
    }

    private static ToolInvocationContext ctxFor(String processId) {
        return new ToolInvocationContext("t", "p", "s", processId, "u");
    }
}
