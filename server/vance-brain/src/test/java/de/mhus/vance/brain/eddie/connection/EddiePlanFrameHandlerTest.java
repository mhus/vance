package de.mhus.vance.brain.eddie.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.PlanProposedNotification;
import de.mhus.vance.api.thinkprocess.ProcessMode;
import de.mhus.vance.api.thinkprocess.ProcessModeChangedNotification;
import de.mhus.vance.api.thinkprocess.TodoItem;
import de.mhus.vance.api.thinkprocess.TodoStatus;
import de.mhus.vance.api.thinkprocess.TodosUpdatedNotification;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.eddie.plan.PlanFusionService;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.shared.eddie.WorkerLinkSnapshot;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies the plan-frame branch updates the right snapshot field,
 * persists, and pushes a fused {@code todos-updated} to Eddie's user
 * session.
 */
class EddiePlanFrameHandlerTest {

    private final ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);
    private final EddieWorkerConnectionPool pool = mock(EddieWorkerConnectionPool.class);
    private final PlanFusionService fusionService = new PlanFusionService();
    private final ClientEventPublisher publisher = mock(ClientEventPublisher.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private final EddiePlanFrameHandler handler = new EddiePlanFrameHandler(
            thinkProcessService, pool, fusionService, publisher, objectMapper);

    @Test
    void todosUpdated_mirrorsTodos_persists_andPushesFusion() {
        WorkerLinkSnapshot link = link("w-1");
        when(pool.findEddieIdForWorker("w-1")).thenReturn(Optional.of("eddie-1"));
        when(thinkProcessService.findById("eddie-1")).thenReturn(Optional.of(eddieWithLink(link,
                List.of(TodoItem.builder().id("2.1").status(TodoStatus.PENDING)
                        .content("Migration in 3 Schritten").build()))));

        TodosUpdatedNotification incoming = TodosUpdatedNotification.builder()
                .processId("w-1")
                .processName("arthur")
                .sessionId("worker-sess")
                .todos(List.of(TodoItem.builder().id("2.1").status(TodoStatus.PENDING)
                        .content("Migration in 3 Schritten").build()))
                .build();

        handler.onPlanFrame(envelope(MessageType.TODOS_UPDATED, incoming), link);

        assertThat(link.getWorkerTodos()).hasSize(1);
        assertThat(link.getLastSeen()).isNotNull();
        verify(thinkProcessService).upsertWorkerLink(eq("eddie-1"), eq(link));

        ArgumentCaptor<TodosUpdatedNotification> pushed =
                ArgumentCaptor.forClass(TodosUpdatedNotification.class);
        verify(publisher).publish(eq("sess-1"), eq(MessageType.TODOS_UPDATED), pushed.capture());
        // Fused notification carries Eddie's processId — and the worker
        // todo got the source-prefix from the fusion service.
        assertThat(pushed.getValue().getProcessId()).isEqualTo("eddie-1");
        assertThat(pushed.getValue().getTodos()).extracting(TodoItem::getId)
                .containsExactly("arthur-projA/2.1");
    }

    @Test
    void planProposed_updatesPlanVersion_andPushesFusion() {
        WorkerLinkSnapshot link = link("w-1");
        when(pool.findEddieIdForWorker("w-1")).thenReturn(Optional.of("eddie-1"));
        when(thinkProcessService.findById("eddie-1"))
                .thenReturn(Optional.of(eddieWithLink(link, List.of())));

        PlanProposedNotification incoming = PlanProposedNotification.builder()
                .processId("w-1").processName("arthur").sessionId("worker-sess")
                .summary("Auth-Refactor in 3 Schritten")
                .planVersion(7)
                .build();

        handler.onPlanFrame(envelope(MessageType.PLAN_PROPOSED, incoming), link);

        assertThat(link.getPlanVersion()).isEqualTo(7);
        verify(thinkProcessService).upsertWorkerLink(eq("eddie-1"), eq(link));
        verify(publisher).publish(eq("sess-1"), eq(MessageType.TODOS_UPDATED), any());
    }

    @Test
    void processModeChanged_updatesWorkerMode() {
        WorkerLinkSnapshot link = link("w-1");
        when(pool.findEddieIdForWorker("w-1")).thenReturn(Optional.of("eddie-1"));
        when(thinkProcessService.findById("eddie-1"))
                .thenReturn(Optional.of(eddieWithLink(link, List.of())));

        ProcessModeChangedNotification incoming = ProcessModeChangedNotification.builder()
                .processId("w-1").processName("arthur").sessionId("worker-sess")
                .oldMode(ProcessMode.NORMAL)
                .newMode(ProcessMode.PLANNING)
                .build();

        handler.onPlanFrame(envelope(MessageType.PROCESS_MODE_CHANGED, incoming), link);

        assertThat(link.getWorkerMode()).isEqualTo(ProcessMode.PLANNING);
        verify(thinkProcessService).upsertWorkerLink(eq("eddie-1"), eq(link));
    }

    @Test
    void unrelatedFrameType_isIgnored() {
        WorkerLinkSnapshot link = link("w-1");

        handler.onPlanFrame(envelope("welcome", null), link);

        assertThat(link.getLastSeen()).isNull();
        verify(thinkProcessService, never()).upsertWorkerLink(any(), any());
        verify(publisher, never()).publish(any(), any(), any());
    }

    @Test
    void unresolvedEddieOwner_skipsPersistence_andSkipsPush() {
        WorkerLinkSnapshot link = link("w-orphan");
        when(pool.findEddieIdForWorker("w-orphan")).thenReturn(Optional.empty());

        TodosUpdatedNotification incoming = TodosUpdatedNotification.builder()
                .processId("w-orphan").todos(new ArrayList<>()).build();

        handler.onPlanFrame(envelope(MessageType.TODOS_UPDATED, incoming), link);

        // In-memory snapshot updated, but no Eddie owner → no persist, no push.
        assertThat(link.getWorkerTodos()).isNotNull();
        verify(thinkProcessService, never()).upsertWorkerLink(any(), any());
        verify(publisher, never()).publish(any(), any(), any());
    }

    private static WorkerLinkSnapshot link(String workerProcessId) {
        return WorkerLinkSnapshot.builder()
                .workerProcessId(workerProcessId)
                .workerProcessName("arthur")
                .workerProjectName("projA")
                .workerSessionId("worker-sess")
                .build();
    }

    private static ThinkProcessDocument eddieWithLink(
            WorkerLinkSnapshot link, List<TodoItem> mirroredTodos) {
        // Use the actual instance, with workerTodos populated by the
        // handler before fusion runs. We simulate the read-after-persist
        // by giving the doc the same link (with whatever the handler
        // mutated on it).
        ThinkProcessDocument eddie = ThinkProcessDocument.builder()
                .id("eddie-1")
                .tenantId("acme")
                .projectId("_user_mike")
                .sessionId("sess-1")
                .name("eddie")
                .build();
        link.setWorkerTodos(new ArrayList<>(mirroredTodos));
        eddie.setWorkerLinks(new ArrayList<>(List.of(link)));
        return eddie;
    }

    private static WebSocketEnvelope envelope(String type, Object data) {
        return WebSocketEnvelope.notification(type, data);
    }
}
