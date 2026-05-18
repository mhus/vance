package de.mhus.vance.brain.action;

import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.brain.hactar.HactarWorkflowService;
import de.mhus.vance.brain.hactar.HactarWorkflowService.HactarWorkflowException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Spawn a Hactar workflow run from a {@link TriggerAction.Workflow}.
 *
 * <p>Async by design — returns {@link ActionOutcome#SCHEDULED} carrying
 * the {@code workflowRunId}. The spawned run reports its own terminal
 * state via the Hactar journal; this executor does not wait.
 *
 * <p>Hactar is an optional subsystem ({@code vance.services.hactar}).
 * When disabled, the executor returns
 * {@link ActionOutcome#TECHNICAL_ERROR} with a clear message instead of
 * blowing up on a missing bean.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public final class WorkflowActionExecutor implements ActionExecutor<TriggerAction.Workflow> {

    private final ObjectProvider<HactarWorkflowService> workflowServiceProvider;

    @Override
    public Class<TriggerAction.Workflow> actionType() {
        return TriggerAction.Workflow.class;
    }

    @Override
    public ActionResult execute(ActionInvocation<TriggerAction.Workflow> invocation) {
        TriggerAction.Workflow action = invocation.action();
        TriggerContext ctx = invocation.context();

        HactarWorkflowService workflowService = workflowServiceProvider.getIfAvailable();
        if (workflowService == null) {
            String msg = "Hactar workflow subsystem is not active (vance.services.hactar=false)";
            log.warn("WorkflowActionExecutor: refusing to spawn '{}' — {}", action.workflow(), msg);
            return ActionResult.failure(ActionOutcome.TECHNICAL_ERROR, msg, null);
        }

        String startedBy = ctx.resolvedRunAs();
        try {
            String runId = workflowService.start(
                    ctx.tenantId(),
                    ctx.projectId(),
                    action.workflow(),
                    action.params(),
                    startedBy);
            log.debug("WorkflowActionExecutor: spawned workflow '{}' runId='{}' startedBy='{}' source='{}'",
                    action.workflow(), runId, startedBy, ctx.sourceTag());
            return ActionResult.scheduled(runId);
        } catch (HactarWorkflowException e) {
            log.warn("WorkflowActionExecutor: workflow '{}' not resolvable: {}",
                    action.workflow(), e.getMessage());
            return ActionResult.failure(ActionOutcome.TECHNICAL_ERROR, e.getMessage(), null);
        } catch (RuntimeException e) {
            log.warn("WorkflowActionExecutor: workflow '{}' start failed: {}",
                    action.workflow(), e.toString());
            return ActionResult.failure(ActionOutcome.TECHNICAL_ERROR, e.getMessage(), null);
        }
    }
}
