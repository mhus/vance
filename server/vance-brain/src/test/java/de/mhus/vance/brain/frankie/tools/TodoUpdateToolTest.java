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
import de.mhus.vance.shared.thinkprocess.TodoPatch;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TodoUpdateToolTest {

    private static final String PROC_ID = "proc-1";

    private ThinkProcessService thinkProcessService;
    private PlanModeEventEmitter emitter;
    private TodoUpdateTool tool;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        emitter = mock(PlanModeEventEmitter.class);
        tool = new TodoUpdateTool(thinkProcessService, emitter);
    }

    @Test
    void parsesPartialPatch_passesAllFieldsToService() {
        when(thinkProcessService.updateTodos(eq(PROC_ID), any())).thenReturn(true);
        when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.of(withTodos(
                TodoItem.builder().id("1").status(TodoStatus.IN_PROGRESS).content("a").build())));

        Map<String, Object> out = tool.invoke(Map.of(
                "items", List.of(
                        Map.of("id", "1", "status", "COMPLETED",
                                "content", "rewritten", "activeForm", "Rewriting"),
                        Map.of("id", "2", "status", "IN_PROGRESS"))),
                ctxFor(PROC_ID));

        assertThat(out).containsEntry("changed", true);
        assertThat(out).containsEntry("applied", 2);

        ArgumentCaptor<List<TodoPatch>> captor = ArgumentCaptor.forClass(List.class);
        verify(thinkProcessService).updateTodos(eq(PROC_ID), captor.capture());
        List<TodoPatch> patches = captor.getValue();
        assertThat(patches).hasSize(2);
        assertThat(patches.get(0).id()).isEqualTo("1");
        assertThat(patches.get(0).status()).isEqualTo(TodoStatus.COMPLETED);
        assertThat(patches.get(0).content()).isEqualTo("rewritten");
        assertThat(patches.get(0).activeForm()).isEqualTo("Rewriting");
        assertThat(patches.get(1).id()).isEqualTo("2");
        assertThat(patches.get(1).status()).isEqualTo(TodoStatus.IN_PROGRESS);
        assertThat(patches.get(1).content()).isNull();
        assertThat(patches.get(1).activeForm()).isNull();
    }

    @Test
    void autoClears_whenAllItemsCompleted() {
        when(thinkProcessService.updateTodos(eq(PROC_ID), any())).thenReturn(true);
        // After update: everything COMPLETED → should trigger auto-clear.
        when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.of(withTodos(
                TodoItem.builder().id("1").status(TodoStatus.COMPLETED).content("a").build(),
                TodoItem.builder().id("2").status(TodoStatus.COMPLETED).content("b").build())));

        Map<String, Object> out = tool.invoke(Map.of(
                "items", List.of(
                        Map.of("id", "2", "status", "COMPLETED"))),
                ctxFor(PROC_ID));

        assertThat(out).containsEntry("cleared", true);
        verify(thinkProcessService).setTodos(eq(PROC_ID), eq(List.of()));
        // Exactly one todos-updated — the auto-clear final emit, with empty list.
        ArgumentCaptor<List<TodoItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(emitter).emitTodosUpdated(any(), captor.capture());
        assertThat(captor.getValue()).isEmpty();
    }

    @Test
    void noAutoClear_whenSomeStillPending() {
        when(thinkProcessService.updateTodos(eq(PROC_ID), any())).thenReturn(true);
        when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.of(withTodos(
                TodoItem.builder().id("1").status(TodoStatus.COMPLETED).content("a").build(),
                TodoItem.builder().id("2").status(TodoStatus.PENDING).content("b").build())));

        Map<String, Object> out = tool.invoke(Map.of(
                "items", List.of(Map.of("id", "1", "status", "COMPLETED"))),
                ctxFor(PROC_ID));

        assertThat(out).containsEntry("cleared", false);
        verify(thinkProcessService, never()).setTodos(any(), any());
        verify(emitter).emitTodosUpdated(any(), any());
    }

    @Test
    void skipsMalformedPatches() {
        when(thinkProcessService.updateTodos(eq(PROC_ID), any())).thenReturn(true);
        when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.of(withTodos(
                TodoItem.builder().id("1").status(TodoStatus.PENDING).content("a").build())));

        tool.invoke(Map.of("items", List.of(
                        Map.of("id", "1", "status", "COMPLETED"),
                        Map.of("id", "", "status", "PENDING"),  // blank id
                        "not a map",
                        Map.of("status", "PENDING"))),           // missing id
                ctxFor(PROC_ID));

        ArgumentCaptor<List<TodoPatch>> captor = ArgumentCaptor.forClass(List.class);
        verify(thinkProcessService).updateTodos(eq(PROC_ID), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
    }

    @Test
    void unknownStatusKeepsPatchButLeavesStatusUnchanged() {
        when(thinkProcessService.updateTodos(eq(PROC_ID), any())).thenReturn(true);
        when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.of(withTodos(
                TodoItem.builder().id("1").status(TodoStatus.PENDING).content("a").build())));

        tool.invoke(Map.of("items", List.of(
                        Map.of("id", "1", "status", "BOGUS", "content", "new content"))),
                ctxFor(PROC_ID));

        ArgumentCaptor<List<TodoPatch>> captor = ArgumentCaptor.forClass(List.class);
        verify(thinkProcessService).updateTodos(eq(PROC_ID), captor.capture());
        TodoPatch p = captor.getValue().get(0);
        assertThat(p.status()).isNull();
        assertThat(p.content()).isEqualTo("new content");
    }

    @Test
    void emptyParsedPatches_skipsServiceCall() {
        Map<String, Object> out = tool.invoke(Map.of(
                "items", List.of(Map.of("id", ""))),
                ctxFor(PROC_ID));

        assertThat(out).containsEntry("applied", 0);
        assertThat(out).containsKey("note");
        verify(thinkProcessService, never()).updateTodos(any(), any());
    }

    @Test
    void requiresProcessScope() {
        ToolInvocationContext noProc = new ToolInvocationContext(
                "t", "p", "s", null, "u");

        assertThatThrownBy(() -> tool.invoke(Map.of("items", List.of()), noProc))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("process scope");
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
