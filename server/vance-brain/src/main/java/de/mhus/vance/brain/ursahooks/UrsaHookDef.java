package de.mhus.vance.brain.ursahooks;

import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.api.ursahooks.UrsaHookEventName;
import de.mhus.vance.api.ursahooks.UrsaHookSource;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Parsed hook YAML document. Immutable record produced by
 * {@link UrsaHookYamlParser}; consumed by the registry and the dispatcher.
 *
 * <p>A hook fires when its {@link #event} is published in the brain.
 * The hook's {@link #action} is dispatched through the central
 * {@code ActionExecutorRegistry} — same pipeline as schedulers and
 * webhooks. See {@code specification/ursahooks.md} and
 * {@code specification/trigger-actions.md}.
 *
 * <p>The action is one of {@code TriggerAction.Recipe},
 * {@code TriggerAction.Script}, or {@code TriggerAction.Workflow} — the
 * disjunction is enforced at parse time.
 */
public record UrsaHookDef(
        String name,
        UrsaHookEventName event,
        UrsaHookSource source,
        boolean enabled,
        @Nullable String description,
        Duration timeout,
        @Nullable List<String> tags,
        String yamlBody,
        @Nullable String createdByUserId,
        TriggerAction action) {

    public String sourceKey() {
        return UrsaHookSourceKeys.sourceFor(event.wireName(), name);
    }

    /**
     * Convenience for UI / logs: the short label of which action
     * variant this hook fires ({@code "recipe"} / {@code "script"} /
     * {@code "workflow"}).
     */
    public String actionType() {
        if (action instanceof TriggerAction.Recipe) return "recipe";
        if (action instanceof TriggerAction.Script) return "script";
        if (action instanceof TriggerAction.Workflow) return "workflow";
        return "unknown";
    }
}
