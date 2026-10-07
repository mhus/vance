package de.mhus.vance.brain.marvin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.marvin.NodeStatus;
import de.mhus.vance.api.marvin.TaskKind;
import de.mhus.vance.api.thinkprocess.TodoItem;
import de.mhus.vance.api.thinkprocess.TodoStatus;
import de.mhus.vance.brain.arthur.PlanModeEventEmitter;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SystemPromptComposer;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.marvin.MarvinNodeDocument;
import de.mhus.vance.shared.marvin.MarvinNodeService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

/**
 * The tree → TodoList projection (decision F8): pure projection of the
 * node documents — item id = node id, open frontier first, capped at 12,
 * FAILED maps to COMPLETED like Zaphod (TodoStatus has no failed state).
 * Mode-independent: the run view shows the same frontier headless.
 */
class MarvinTreeTodoProjectionTest {

    private final MarvinNodeService nodeService = mock(MarvinNodeService.class);
    private final ThinkProcessService processes = mock(ThinkProcessService.class);
    private final PlanModeEventEmitter planEvents = mock(PlanModeEventEmitter.class);

    @SuppressWarnings("unchecked")
    private final MarvinEngine engine = new MarvinEngine(
            nodeService,
            new MarvinProperties(),
            mock(MaximegalonService.class),
            processes,
            mock(de.mhus.vance.shared.chat.ChatMessageService.class),
            mock(RecipeResolver.class),
            mock(RecipeLoader.class),
            mock(PhaseOutputParser.class),
            mock(PlanSnapshotRenderer.class),
            mock(de.mhus.vance.brain.progress.LlmCallTracker.class),
            mock(de.mhus.vance.brain.progress.ProgressEmitter.class),
            mock(de.mhus.vance.brain.thinkengine.EnginePromptResolver.class),
            mock(SystemPromptComposer.class),
            mock(de.mhus.vance.brain.ai.EngineChatFactory.class),
            mock(ObjectMapper.class),
            mock(ProcessEventEmitter.class),
            mock(de.mhus.vance.brain.scheduling.LaneScheduler.class),
            mock(DocumentExpander.class),
            mock(de.mhus.vance.shared.workspace.WorkspaceService.class),
            mock(de.mhus.vance.shared.document.DocumentService.class),
            mock(ObjectProvider.class),
            mock(de.mhus.vance.brain.inherit.ParentContextSpawnHelper.class),
            mock(de.mhus.vance.brain.inherit.ParentContextRenderer.class),
            mock(MarvinSessionLoop.class),
            planEvents);

    @Test
    void nodesProjectWithStableIdsAndStatuses() {
        MarvinNodeDocument root = node("root-1", NodeStatus.DONE, "Find the best database");
        MarvinNodeDocument running = node("n-2", NodeStatus.RUNNING, "Compare Mongo and Postgres");
        MarvinNodeDocument pending = node("n-3", NodeStatus.PENDING, "Bench the top pick");
        MarvinNodeDocument failed = node("n-4", NodeStatus.FAILED, "Ask the community");
        when(nodeService.listAll("marvin-1")).thenReturn(List.of(root, running, pending, failed));

        engine.updateTreeTodos(process());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TodoItem>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(processes).setTodos(anyString(), itemsCaptor.capture());
        List<TodoItem> items = itemsCaptor.getValue();
        // Open frontier first, then the terminal nodes.
        assertThat(items).extracting(TodoItem::getId).containsExactly("n-2", "n-3", "root-1", "n-4");
        assertThat(items)
                .extracting(TodoItem::getStatus)
                .containsExactly(
                        TodoStatus.IN_PROGRESS, TodoStatus.PENDING, TodoStatus.COMPLETED, TodoStatus.COMPLETED);
        verify(planEvents).emitTodosUpdated(any(), anyList());
    }

    @Test
    void waitingNodesProjectInProgressWithAnActiveForm() {
        MarvinNodeDocument waiting = node("n-1", NodeStatus.WAITING, "Needs a human decision");
        when(nodeService.listAll("marvin-1")).thenReturn(List.of(waiting));

        engine.updateTreeTodos(process());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TodoItem>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(processes).setTodos(anyString(), itemsCaptor.capture());
        TodoItem item = itemsCaptor.getValue().get(0);
        assertThat(item.getStatus()).isEqualTo(TodoStatus.IN_PROGRESS);
        assertThat(item.getActiveForm()).isEqualTo("Waiting for a human answer");
    }

    @Test
    void theListIsCappedAtTwelve_FrontierFirst() {
        List<MarvinNodeDocument> many = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            many.add(node("n-" + i, i < 3 ? NodeStatus.PENDING : NodeStatus.DONE, "task " + i));
        }
        when(nodeService.listAll("marvin-1")).thenReturn(many);

        engine.updateTreeTodos(process());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TodoItem>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(processes).setTodos(anyString(), itemsCaptor.capture());
        List<TodoItem> items = itemsCaptor.getValue();
        assertThat(items).hasSize(12);
        // The open frontier leads even when the settled tail is longer.
        assertThat(items.subList(0, 3)).extracting(TodoItem::getId).containsExactly("n-0", "n-1", "n-2");
    }

    @Test
    void withoutATreeNothingIsProjected() {
        when(nodeService.listAll("marvin-1")).thenReturn(List.of());

        engine.updateTreeTodos(process());

        verify(processes, never()).setTodos(any(), anyList());
        verify(planEvents, never()).emitTodosUpdated(any(), anyList());
    }

    // ── helpers ─────────────────────────────────────────────────────

    private static ThinkProcessDocument process() {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId("marvin-1");
        p.setTenantId("t");
        p.setProjectId("p");
        p.setSessionId("s");
        return p;
    }

    private static MarvinNodeDocument node(String id, NodeStatus status, String goal) {
        MarvinNodeDocument n = new MarvinNodeDocument();
        n.setId(id);
        n.setTenantId("t");
        n.setProcessId("marvin-1");
        n.setGoal(goal);
        n.setTaskKind(TaskKind.WORKER);
        n.setStatus(status);
        n.setArtifacts(new LinkedHashMap<>());
        return n;
    }
}
