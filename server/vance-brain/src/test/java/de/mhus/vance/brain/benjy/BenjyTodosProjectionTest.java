package de.mhus.vance.brain.benjy;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.TodoItem;
import de.mhus.vance.api.thinkprocess.TodoStatus;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.arthur.PlanModeEventEmitter;
import de.mhus.vance.brain.tools.worktarget.WorkTargetService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the todos-projection wiring (§9): the projection in
 * {@code ThinkProcessService} is pure persistence — the engine must add the
 * derived {@code todos-updated} frame after every mutation so foot / Web-UI
 * render the same progress box Frankie's {@code todo_*} tools drive, and must
 * drop it (empty list + empty frame) at DONE.
 */
class BenjyTodosProjectionTest {

    private static final String PROC_ID = "benjy-1";

    private ThinkProcessService thinkProcessService;
    private PlanModeEventEmitter emitter;
    private BenjyEngine engine;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        emitter = mock(PlanModeEventEmitter.class);
        engine = new BenjyEngine(
                thinkProcessService,
                JsonMapper.builder().build(),
                mock(LightLlmService.class),
                mock(BenjyWorkerSpawner.class),
                mock(MetricService.class),
                mock(WorkTargetService.class),
                mock(WorkspaceService.class),
                emitter);
    }

    private static ThinkProcessDocument doc(List<TodoItem> todos) {
        ThinkProcessDocument doc = new ThinkProcessDocument();
        doc.setId(PROC_ID);
        doc.setName("benjy chat process");
        doc.setSessionId("sess-1");
        doc.setTodos(todos);
        return doc;
    }

    @Test
    void emit_refreshesFromPersistence_andForwardsTodos() {
        List<TodoItem> todos = List.of(TodoItem.builder()
                .id("1")
                .content("item one")
                .status(TodoStatus.IN_PROGRESS)
                .build());
        ThinkProcessDocument refreshed = doc(todos);
        when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.of(refreshed));

        engine.emitTodosProjection(doc(List.of()));

        // The frame carries the persisted state, not the engine's stale copy.
        verify(emitter).emitTodosUpdated(refreshed, todos);
    }

    @Test
    void emit_staysSilent_whenProcessIsGone() {
        when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.empty());

        engine.emitTodosProjection(doc(List.of()));

        verifyNoInteractions(emitter);
    }

    @Test
    void clearAtDone_persistsEmptyList_andEmitsEmptyFrame() {
        BenjyState state = new BenjyState();
        state.getItems().add(BenjyState.Item.of("1", "item one"));
        ThinkProcessDocument process = doc(List.of());

        engine.clearTodosProjection(process, state);

        verify(thinkProcessService).setTodos(PROC_ID, List.of());
        verify(emitter).emitTodosUpdated(process, List.of());
    }

    @Test
    void clearAtDone_noItems_noWriteNoFrame() {
        engine.clearTodosProjection(doc(List.of()), new BenjyState());

        verify(thinkProcessService, never()).setTodos(anyString(), anyList());
        verifyNoInteractions(emitter);
    }
}
