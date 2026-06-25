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

class TodoRemoveToolTest {

    private static final String PROC_ID = "proc-1";

    private ThinkProcessService thinkProcessService;
    private PlanModeEventEmitter emitter;
    private TodoRemoveTool tool;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        emitter = mock(PlanModeEventEmitter.class);
        tool = new TodoRemoveTool(thinkProcessService, emitter);
    }

    @Test
    void removesByIds_emitsTodosUpdated() {
        when(thinkProcessService.removeTodos(eq(PROC_ID), any())).thenReturn(2);
        ThinkProcessDocument refreshed = new ThinkProcessDocument();
        refreshed.setId(PROC_ID);
        refreshed.setTodos(List.of(
                TodoItem.builder().id("1").status(TodoStatus.PENDING).content("a").build()));
        when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.of(refreshed));

        Map<String, Object> out = tool.invoke(Map.of(
                "ids", List.of("3", "5")), ctxFor(PROC_ID));

        assertThat(out).containsEntry("ok", true);
        assertThat(out).containsEntry("removed", 2);
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(thinkProcessService).removeTodos(eq(PROC_ID), captor.capture());
        assertThat(captor.getValue()).containsExactly("3", "5");
        verify(emitter).emitTodosUpdated(any(), any());
    }

    @Test
    void skipsNonStringEntries() {
        when(thinkProcessService.removeTodos(eq(PROC_ID), any())).thenReturn(1);
        when(thinkProcessService.findById(PROC_ID))
                .thenReturn(Optional.of(new ThinkProcessDocument()));

        tool.invoke(Map.of("ids", List.of("3", 42, "", "5")), ctxFor(PROC_ID));

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(thinkProcessService).removeTodos(eq(PROC_ID), captor.capture());
        assertThat(captor.getValue()).containsExactly("3", "5");
    }

    @Test
    void zeroRemoved_skipsEmit() {
        when(thinkProcessService.removeTodos(eq(PROC_ID), any())).thenReturn(0);

        Map<String, Object> out = tool.invoke(Map.of(
                "ids", List.of("nope")), ctxFor(PROC_ID));

        assertThat(out).containsEntry("removed", 0);
        verify(emitter, never()).emitTodosUpdated(any(), any());
    }

    @Test
    void requiresProcessScope() {
        ToolInvocationContext noProc = new ToolInvocationContext(
                "t", "p", "s", null, "u");

        assertThatThrownBy(() -> tool.invoke(Map.of("ids", List.of()), noProc))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("process scope");
    }

    @Test
    void rejectsMissingIds() {
        assertThatThrownBy(() -> tool.invoke(Map.of(), ctxFor(PROC_ID)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("ids");
    }

    private static ToolInvocationContext ctxFor(String processId) {
        return new ToolInvocationContext("t", "p", "s", processId, "u");
    }
}
