package de.mhus.vance.brain.action;

import de.mhus.vance.api.action.TriggerAction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Dispatcher from {@link TriggerAction} subtype to the matching
 * {@link ActionExecutor}. Built once at boot from the Spring
 * application context; rejects duplicate registrations to keep the
 * mapping unambiguous.
 *
 * <p>Triggers (scheduler, event, workflow-task, …) ask the registry
 * for an executor and pass the invocation in — there is no per-trigger
 * if/else over action variants anywhere in the codebase.
 */
@Component
@Slf4j
public final class ActionExecutorRegistry {

    private final Map<Class<? extends TriggerAction>, ActionExecutor<?>> byType;

    public ActionExecutorRegistry(List<ActionExecutor<?>> executors) {
        Map<Class<? extends TriggerAction>, ActionExecutor<?>> map = new HashMap<>();
        for (ActionExecutor<?> exec : executors) {
            Class<? extends TriggerAction> type = exec.actionType();
            ActionExecutor<?> prev = map.put(type, exec);
            if (prev != null) {
                throw new IllegalStateException(
                        "Duplicate ActionExecutor registration for " + type.getName()
                                + ": " + prev.getClass().getName()
                                + " vs. " + exec.getClass().getName());
            }
        }
        this.byType = Map.copyOf(map);
        log.debug("ActionExecutorRegistry: {} executor(s) registered: {}",
                byType.size(), byType.keySet().stream()
                        .map(Class::getSimpleName).sorted().toList());
    }

    /**
     * Look up the executor for an action's runtime type and run it.
     * Throws {@link IllegalStateException} when no executor is
     * registered for the action's subtype — that's a wiring bug, not
     * an action error.
     */
    public ActionResult execute(TriggerAction action,
                                TriggerContext context,
                                TriggerKind triggerKind) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        ActionExecutor exec = byType.get(action.getClass());
        if (exec == null) {
            throw new IllegalStateException(
                    "No ActionExecutor registered for " + action.getClass().getName());
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        ActionInvocation invocation = new ActionInvocation(action, context, triggerKind);
        return exec.execute(invocation);
    }

    /** Test-only / introspection accessor. */
    public boolean hasExecutorFor(Class<? extends TriggerAction> type) {
        return byType.containsKey(type);
    }
}
