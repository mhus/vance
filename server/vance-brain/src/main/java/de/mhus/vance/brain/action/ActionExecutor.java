package de.mhus.vance.brain.action;

import de.mhus.vance.api.action.TriggerAction;

/**
 * Strategy interface — one implementation per {@link TriggerAction}
 * variant. {@link ActionExecutorRegistry} discovers implementations as
 * Spring beans and dispatches by {@link #actionType()}.
 *
 * <p>Executors are stateless and reusable across trigger surfaces. The
 * surface-specific identity (tenant, project, runAs, correlationId)
 * arrives via {@link TriggerContext}; the surface-specific lifecycle
 * (e.g. workflow task completion) wraps the executor result, it does
 * not live inside it.
 *
 * @param <A> concrete action subtype this executor handles
 */
public interface ActionExecutor<A extends TriggerAction> {

    /** The concrete subtype dispatched to this executor. */
    Class<A> actionType();

    /**
     * Run the action. Synchronous executors complete the work and
     * return a sync outcome ({@code SUCCESS} / failure family).
     * Async executors spawn a Process or Workflow-Run, return
     * {@link ActionOutcome#SCHEDULED} with the spawned id, and the
     * spawned entity reports its own terminal state separately.
     *
     * <p>Must not throw for known failure modes — return an
     * {@link ActionResult} with a failure outcome instead. Throwing is
     * reserved for unexpected programming errors that the caller would
     * not know how to handle.
     */
    ActionResult execute(ActionInvocation<A> invocation);
}
