package de.mhus.vance.brain.action;

import de.mhus.vance.api.action.TriggerAction;

/**
 * Bundle handed to an {@link ActionExecutor}: the parsed action, the
 * trigger-supplied scope/identity, and which kind of trigger fired it.
 *
 * <p>Parametrised on the concrete {@link TriggerAction} subtype so
 * executors can declare their input type without an unchecked cast.
 */
public record ActionInvocation<A extends TriggerAction>(
        A action,
        TriggerContext context,
        TriggerKind triggerKind) {

    public ActionInvocation {
        if (action == null) {
            throw new IllegalArgumentException("ActionInvocation.action must not be null");
        }
        if (context == null) {
            throw new IllegalArgumentException("ActionInvocation.context must not be null");
        }
        if (triggerKind == null) {
            throw new IllegalArgumentException("ActionInvocation.triggerKind must not be null");
        }
    }
}
