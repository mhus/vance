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

class TodoWriteToolTest {

    private static final String PROC_ID = "proc-1";

    private ThinkProcessService thinkProcessService;
    private PlanModeEventEmitter emitter;
    private TodoWriteTool tool;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        emitter = mock(PlanModeEventEmitter.class);
        tool = new TodoWriteTool(thinkProcessService, emitter);

        ThinkProcessDocument refreshed = new ThinkProcessDocument();
        refreshed.setId(PROC_ID);
        when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.of(refreshed));
        when(thinkProcessService.setTodos(eq(PROC_ID), any())).thenReturn(true);
    }

    @Test
    void writes_validItems_emitsBothNotifications() {
        Map<String, Object> params = Map.of(
                "items", List.of(
                        Map.of("id", "1", "content", "Read parser"),
                        Map.of("id", "2", "content", "Add streaming variant",
                                "activeForm", "Adding streaming variant"),
                        Map.of("id", "3", "content", "Migrate callers",
                                "status", "IN_PROGRESS")));

        Map<String, Object> out = tool.invoke(params, ctxFor(PROC_ID));

        assertThat(out).containsEntry("ok", true);
        assertThat(out).containsEntry("count", 3);

        ArgumentCaptor<List<TodoItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(thinkProcessService).setTodos(eq(PROC_ID), captor.capture());
        List<TodoItem> persisted = captor.getValue();
        assertThat(persisted).hasSize(3);
        assertThat(persisted.get(0).getId()).isEqualTo("1");
        assertThat(persisted.get(0).getStatus()).isEqualTo(TodoStatus.PENDING);
        assertThat(persisted.get(1).getActiveForm()).isEqualTo("Adding streaming variant");
        assertThat(persisted.get(2).getStatus()).isEqualTo(TodoStatus.IN_PROGRESS);

        verify(emitter).emitTodosUpdated(any(), any());
        verify(emitter).emitPlanProposed(any(), eq(null), eq(1));
    }

    @Test
    void skipsMalformedEntries_neverAborts() {
        Map<String, Object> params = Map.of(
                "items", List.of(
                        Map.of("id", "1", "content", "Good"),
                        Map.of("id", "", "content", "Missing id"),
                        Map.of("id", "3"),  // missing content
                        "not a map",         // wrong shape
                        Map.of("id", "4", "content", "Also good",
                                "status", "BOGUS_STATUS")));  // unknown status → PENDING

        Map<String, Object> out = tool.invoke(params, ctxFor(PROC_ID));

        assertThat(out).containsEntry("count", 2);
        ArgumentCaptor<List<TodoItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(thinkProcessService).setTodos(eq(PROC_ID), captor.capture());
        List<TodoItem> persisted = captor.getValue();
        assertThat(persisted).hasSize(2);
        assertThat(persisted.get(1).getStatus()).isEqualTo(TodoStatus.PENDING);
    }

    @Test
    void emptyList_clearsTodos() {
        Map<String, Object> params = Map.of("items", List.of());

        Map<String, Object> out = tool.invoke(params, ctxFor(PROC_ID));

        assertThat(out).containsEntry("count", 0);
        verify(thinkProcessService).setTodos(eq(PROC_ID), eq(List.of()));
    }

    @Test
    void requiresProcessScope() {
        ToolInvocationContext noProcess = new ToolInvocationContext(
                "tenant", "proj", "sess", null, "user");

        assertThatThrownBy(() -> tool.invoke(Map.of("items", List.of()), noProcess))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("process scope");
        verify(thinkProcessService, never()).setTodos(any(), any());
    }

    @Test
    void rejectsMissingItems() {
        assertThatThrownBy(() -> tool.invoke(Map.of(), ctxFor(PROC_ID)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("items");
    }

    @Test
    void throwsWhenProcessNotFound() {
        when(thinkProcessService.setTodos(eq(PROC_ID), any())).thenReturn(false);

        assertThatThrownBy(() ->
                tool.invoke(Map.of("items", List.of(Map.of("id", "1", "content", "X"))),
                        ctxFor(PROC_ID)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not found");
    }

    private static ToolInvocationContext ctxFor(String processId) {
        return new ToolInvocationContext(
                "tenant-x", "proj-1", "sess-y", processId, "user-1");
    }
}
