package de.mhus.vance.brain.lunkwill.tools;

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

        ThinkProcessDocument refreshed = new ThinkProcessDocument();
        refreshed.setId(PROC_ID);
        refreshed.setTodos(List.of(
                TodoItem.builder().id("1").status(TodoStatus.COMPLETED).content("a").build(),
                TodoItem.builder().id("2").status(TodoStatus.IN_PROGRESS).content("b").build()));
        when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.of(refreshed));
    }

    @Test
    void parsesUpdates_callsService_emitsNotification() {
        when(thinkProcessService.updateTodoStatuses(eq(PROC_ID), any())).thenReturn(true);

        Map<String, Object> params = Map.of(
                "updates", List.of(
                        Map.of("id", "1", "status", "IN_PROGRESS"),
                        Map.of("id", "2", "status", "COMPLETED")));

        Map<String, Object> out = tool.invoke(params, ctxFor(PROC_ID));

        assertThat(out).containsEntry("ok", true);
        assertThat(out).containsEntry("applied", 2);
        assertThat(out).containsEntry("changed", true);

        ArgumentCaptor<Map<String, TodoStatus>> captor = ArgumentCaptor.forClass(Map.class);
        verify(thinkProcessService).updateTodoStatuses(eq(PROC_ID), captor.capture());
        assertThat(captor.getValue())
                .containsEntry("1", TodoStatus.IN_PROGRESS)
                .containsEntry("2", TodoStatus.COMPLETED);

        verify(emitter).emitTodosUpdated(any(), any());
    }

    @Test
    void skipsMalformedEntries_butStillCallsService_whenAtLeastOneValid() {
        when(thinkProcessService.updateTodoStatuses(eq(PROC_ID), any())).thenReturn(true);

        Map<String, Object> params = Map.of(
                "updates", List.of(
                        Map.of("id", "1", "status", "COMPLETED"),
                        Map.of("id", "", "status", "PENDING"),    // missing id
                        Map.of("id", "x", "status", "BOGUS"),      // unknown status
                        "not a map",
                        Map.of("id", "y")));                        // missing status

        Map<String, Object> out = tool.invoke(params, ctxFor(PROC_ID));

        assertThat(out).containsEntry("applied", 1);
        ArgumentCaptor<Map<String, TodoStatus>> captor = ArgumentCaptor.forClass(Map.class);
        verify(thinkProcessService).updateTodoStatuses(eq(PROC_ID), captor.capture());
        assertThat(captor.getValue()).hasSize(1).containsKey("1");
    }

    @Test
    void emptyParsedUpdates_skipsServiceCall_andReportsNote() {
        Map<String, Object> params = Map.of(
                "updates", List.of(Map.of("id", "", "status", "")));

        Map<String, Object> out = tool.invoke(params, ctxFor(PROC_ID));

        assertThat(out).containsEntry("applied", 0);
        assertThat(out).containsEntry("ok", true);
        assertThat(out).containsKey("note");
        verify(thinkProcessService, never()).updateTodoStatuses(any(), any());
        verify(emitter, never()).emitTodosUpdated(any(), any());
    }

    @Test
    void serviceReturnsFalse_noEmit() {
        // Optimistic-lock exhaustion or no matching items in document
        when(thinkProcessService.updateTodoStatuses(eq(PROC_ID), any())).thenReturn(false);

        Map<String, Object> out = tool.invoke(Map.of(
                "updates", List.of(Map.of("id", "1", "status", "COMPLETED"))),
                ctxFor(PROC_ID));

        assertThat(out).containsEntry("changed", false);
        verify(emitter, never()).emitTodosUpdated(any(), any());
    }

    @Test
    void requiresProcessScope() {
        ToolInvocationContext noProcess = new ToolInvocationContext(
                "tenant", "proj", "sess", null, "user");

        assertThatThrownBy(() -> tool.invoke(Map.of("updates", List.of()), noProcess))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("process scope");
    }

    @Test
    void rejectsMissingUpdates() {
        assertThatThrownBy(() -> tool.invoke(Map.of(), ctxFor(PROC_ID)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("updates");
    }

    private static ToolInvocationContext ctxFor(String processId) {
        return new ToolInvocationContext(
                "tenant-x", "proj-1", "sess-y", processId, "user-1");
    }
}
