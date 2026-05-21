package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.api.magrathea.MagratheaWorkflowSource;
import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.shared.magrathea.MagratheaBoundsSpec;
import de.mhus.vance.shared.magrathea.MagratheaRetrySpec;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.inbox.InboxItemService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GateTaskExecutorTest {

    private final InboxItemService inboxService = mock(InboxItemService.class);
    private final MagratheaTaskService taskService = mock(MagratheaTaskService.class);
    private final de.mhus.vance.shared.magrathea.MagratheaTimerService timerService =
            mock(de.mhus.vance.shared.magrathea.MagratheaTimerService.class);
    private final GateTaskExecutor executor = new GateTaskExecutor(
            inboxService, taskService, timerService);

    @Test
    void approval_gate_creates_item_links_task_and_returns_async() {
        when(inboxService.create(any())).thenAnswer(inv -> {
            InboxItemDocument doc = inv.getArgument(0);
            doc.setId("inbox-1");
            return doc;
        });

        Optional<TaskOutcome> outcome = executor.execute(ctx(gateState(Map.of(
                "kind", "APPROVAL",
                "title", "Approve PR?",
                "body", "merge if ok",
                "tags", List.of("pr-review"),
                "assignedTo", "@maintainers"))));

        assertThat(outcome).isEmpty();

        ArgumentCaptor<InboxItemDocument> captor = ArgumentCaptor.captor();
        verify(inboxService).create(captor.capture());
        InboxItemDocument created = captor.getValue();
        assertThat(created.getType()).isEqualTo(InboxItemType.APPROVAL);
        assertThat(created.getTitle()).isEqualTo("Approve PR?");
        assertThat(created.getBody()).isEqualTo("merge if ok");
        assertThat(created.getAssignedToUserId()).isEqualTo("@maintainers");
        assertThat(created.getTags()).contains("pr-review");
        assertThat(created.isRequiresAction()).isTrue();
        assertThat(created.getPayload())
                .containsEntry("kind", GateTaskExecutor.PAYLOAD_KIND)
                .containsEntry("workflowRunId", "r1")
                .containsEntry("workflowName", "noop")
                .containsEntry("workflowState", "review");

        verify(taskService).linkInboxItem("task-1", "inbox-1");
    }

    @Test
    void decision_gate_includes_options_in_payload() {
        when(inboxService.create(any())).thenAnswer(inv -> {
            InboxItemDocument doc = inv.getArgument(0);
            doc.setId("inbox-2");
            return doc;
        });

        executor.execute(ctx(gateState(Map.of(
                "kind", "DECISION",
                "title", "Pick a path",
                "options", List.of("approve", "reject", "defer")))));

        ArgumentCaptor<InboxItemDocument> captor = ArgumentCaptor.captor();
        verify(inboxService).create(captor.capture());
        assertThat(captor.getValue().getPayload())
                .containsEntry("options", List.of("approve", "reject", "defer"));
        assertThat(captor.getValue().getType()).isEqualTo(InboxItemType.DECISION);
    }

    @Test
    void missing_inbox_block_fails() {
        Optional<TaskOutcome> outcome = executor.execute(ctx(gateState(null)));
        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_FAILURE);
        assertThat(outcome.get().errorMessage()).contains("'inbox:'");
    }

    @Test
    void missing_title_fails() {
        Optional<TaskOutcome> outcome = executor.execute(ctx(gateState(Map.of(
                "kind", "APPROVAL"))));
        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_FAILURE);
        assertThat(outcome.get().errorMessage()).contains("title");
    }

    @Test
    void invalid_kind_fails() {
        Optional<TaskOutcome> outcome = executor.execute(ctx(gateState(Map.of(
                "kind", "OUTPUT_TEXT",          // not a gate-interactive kind
                "title", "x"))));
        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_FAILURE);
        assertThat(outcome.get().errorMessage()).contains("APPROVAL/DECISION/FEEDBACK");
    }

    @Test
    void assignedTo_falls_back_to_startedBy_then_system() {
        when(inboxService.create(any())).thenAnswer(inv -> {
            InboxItemDocument d = inv.getArgument(0);
            d.setId("ix");
            return d;
        });

        executor.execute(ctx(gateState(Map.of(
                "kind", "APPROVAL",
                "title", "x"))));

        ArgumentCaptor<InboxItemDocument> captor = ArgumentCaptor.captor();
        verify(inboxService).create(captor.capture());
        // ctx.startedBy is "alice" — the fallback chain picks it.
        assertThat(captor.getValue().getAssignedToUserId()).isEqualTo("alice");
    }

    @Test
    void criticality_parses_case_insensitive_with_default() {
        when(inboxService.create(any())).thenAnswer(inv -> {
            InboxItemDocument d = inv.getArgument(0);
            d.setId("ix");
            return d;
        });

        executor.execute(ctx(gateState(Map.of(
                "kind", "APPROVAL",
                "title", "x",
                "criticality", "critical"))));

        ArgumentCaptor<InboxItemDocument> captor = ArgumentCaptor.captor();
        verify(inboxService).create(captor.capture());
        assertThat(captor.getValue().getCriticality()).isEqualTo(Criticality.CRITICAL);
    }

    @Test
    void timeout_seconds_schedules_a_timeout_timer() {
        when(inboxService.create(any())).thenAnswer(inv -> {
            InboxItemDocument doc = inv.getArgument(0);
            doc.setId("inbox-timeout");
            return doc;
        });

        executor.execute(new MagratheaTaskContext(
                "acme", "proj", "r1", "task-1", "alice",
                new ResolvedMagratheaWorkflow("noop", "", MagratheaWorkflowSource.PROJECT,
                        null, null, null, null, "start",
                        Map.of(), Map.of(), MagratheaBoundsSpec.empty(), List.of(), List.of()),
                new MagratheaStateSpec(
                        "review", MagratheaTaskType.GATE_TASK,
                        null, /* timeoutSeconds */ 60,
                        null,
                        Map.of(), Map.of(),
                        List.of(),
                        MagratheaRetrySpec.none(),
                        Map.of("inbox", Map.of("kind", "APPROVAL", "title", "x"))),
                Map.of(), Map.of()));

        org.mockito.ArgumentCaptor<de.mhus.vance.shared.magrathea.MagratheaTimerDocument> captor =
                org.mockito.ArgumentCaptor.captor();
        verify(timerService).insert(captor.capture());
        assertThat(captor.getValue().getLinkedTaskId()).isEqualTo("task-1");
        assertThat(captor.getValue().getFiredOutcome()).isEqualTo(GateTaskExecutor.OUTCOME_TIMEOUT);
    }

    @Test
    void no_timeoutSeconds_does_not_schedule_a_timer() {
        when(inboxService.create(any())).thenAnswer(inv -> {
            InboxItemDocument doc = inv.getArgument(0);
            doc.setId("inbox-x");
            return doc;
        });

        executor.execute(ctx(gateState(Map.of("kind", "APPROVAL", "title", "x"))));

        verify(timerService, never()).insert(any());
    }

    @Test
    void inbox_create_failure_does_not_link_and_returns_failure() {
        when(inboxService.create(any())).thenThrow(new RuntimeException("dup"));

        Optional<TaskOutcome> outcome = executor.execute(ctx(gateState(Map.of(
                "kind", "APPROVAL", "title", "x"))));

        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_FAILURE);
        assertThat(outcome.get().errorMessage()).contains("dup");
        verify(taskService, never()).linkInboxItem(any(), any());
    }

    // ─────── helpers ───────

    private static MagratheaStateSpec gateState(@org.jspecify.annotations.Nullable Map<String, Object> inbox) {
        Map<String, Object> spec = new LinkedHashMap<>();
        if (inbox != null) spec.put("inbox", inbox);
        return new MagratheaStateSpec(
                "review",
                MagratheaTaskType.GATE_TASK,
                null, null, null,
                Map.of(), Map.of(),
                List.of(),
                MagratheaRetrySpec.none(),
                spec);
    }

    private static MagratheaTaskContext ctx(MagratheaStateSpec state) {
        return new MagratheaTaskContext(
                "acme", "proj", "r1", "task-1", "alice",
                new ResolvedMagratheaWorkflow("noop", "", MagratheaWorkflowSource.PROJECT,
                        null, null, null, null, "start",
                        Map.of(), Map.of(), MagratheaBoundsSpec.empty(), List.of(), List.of()),
                state, Map.of(), Map.of());
    }
}
