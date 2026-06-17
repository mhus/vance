package de.mhus.vance.brain.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.brain.magrathea.MagratheaWorkflowService;
import de.mhus.vance.brain.magrathea.MagratheaWorkflowService.MagratheaWorkflowException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class WorkflowActionExecutorTest {

    private final TriggerContext ctx = TriggerContext.standalone(
            "t1", "p1", "alice", "corr-1", "scheduler:foo", null);

    @Test
    void start_succeeds_returns_scheduled_with_runId() {
        MagratheaWorkflowService svc = mock(MagratheaWorkflowService.class);
        when(svc.start(eq("t1"), eq("p1"), eq("pr-review"), any(), eq("alice")))
                .thenReturn("run-123");

        ActionResult r = newExecutor(svc).execute(new ActionInvocation<>(
                new TriggerAction.Workflow("pr-review", Map.of("pr_url", "x"), null),
                ctx,
                TriggerKind.SCHEDULER));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.SCHEDULED);
        assertThat(r.spawnedId()).isEqualTo("run-123");
        assertThat(r.errorMessage()).isNull();
    }

    @Test
    void magrathea_disabled_returns_technical_error_without_calling_service() {
        ObjectProvider<MagratheaWorkflowService> provider = providerOf(null);
        WorkflowActionExecutor exec = new WorkflowActionExecutor(provider);

        ActionResult r = exec.execute(new ActionInvocation<>(
                new TriggerAction.Workflow("any", null, null),
                ctx,
                TriggerKind.SCHEDULER));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.TECHNICAL_ERROR);
        assertThat(r.errorMessage()).contains("Magrathea workflow subsystem is not active");
    }

    @Test
    void workflow_not_found_maps_to_technical_error() {
        MagratheaWorkflowService svc = mock(MagratheaWorkflowService.class);
        when(svc.start(any(), any(), any(), any(), any()))
                .thenThrow(new MagratheaWorkflowException("Workflow 'gone' not found in cascade"));

        ActionResult r = newExecutor(svc).execute(new ActionInvocation<>(
                new TriggerAction.Workflow("gone", null, null),
                ctx,
                TriggerKind.SCHEDULER));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.TECHNICAL_ERROR);
        assertThat(r.errorMessage()).contains("not found");
    }

    @Test
    void generic_runtime_exception_maps_to_technical_error() {
        MagratheaWorkflowService svc = mock(MagratheaWorkflowService.class);
        when(svc.start(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("mongo down"));

        ActionResult r = newExecutor(svc).execute(new ActionInvocation<>(
                new TriggerAction.Workflow("audit", null, null),
                ctx,
                TriggerKind.EVENT));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.TECHNICAL_ERROR);
        assertThat(r.errorMessage()).contains("mongo down");
    }

    @Test
    void runAs_passed_through_to_service() {
        MagratheaWorkflowService svc = mock(MagratheaWorkflowService.class);
        when(svc.start(any(), any(), any(), any(), any())).thenReturn("run-x");
        TriggerContext customCtx = TriggerContext.standalone(
                "t1", "p1", "ci-bot", null, null, null);

        newExecutor(svc).execute(new ActionInvocation<>(
                new TriggerAction.Workflow("audit", null, null),
                customCtx,
                TriggerKind.SCHEDULER));

        verify(svc).start("t1", "p1", "audit", null, "ci-bot");
    }

    @Test
    void actionType_returns_workflow_class() {
        assertThat(newExecutor(mock(MagratheaWorkflowService.class)).actionType())
                .isEqualTo(TriggerAction.Workflow.class);
    }

    @Test
    void service_never_called_when_magrathea_disabled() {
        MagratheaWorkflowService svc = mock(MagratheaWorkflowService.class);
        ObjectProvider<MagratheaWorkflowService> provider = providerOf(null);

        WorkflowActionExecutor exec = new WorkflowActionExecutor(provider);
        exec.execute(new ActionInvocation<>(
                new TriggerAction.Workflow("x", null, null), ctx, TriggerKind.SCHEDULER));

        verify(svc, never()).start(any(), any(), any(), any(), any());
    }

    // ──────────────────── Helpers ────────────────────

    private static WorkflowActionExecutor newExecutor(MagratheaWorkflowService svc) {
        return new WorkflowActionExecutor(providerOf(svc));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MagratheaWorkflowService> providerOf(MagratheaWorkflowService bean) {
        ObjectProvider<MagratheaWorkflowService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }
}
