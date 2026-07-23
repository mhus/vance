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
        boolean privileged,
        TriggerAction action) {

    public String sourceKey() {
        return UrsaHookSourceKeys.sourceFor(event.wireName(), name);
    }

    /**
     * Copy carrying the resolved {@code privileged} flag of the source
     * document. Set by {@link UrsaHookLoader} from
     * {@code DocumentDocument.isPrivileged()}; the parser can't know it
     * (it's {@code $meta}, not YAML body). Gates {@code action.runAs()}
     * impersonation in the dispatcher — see
     * {@code planning/permission-system-concept.md} §4.3a.
     */
    public UrsaHookDef withPrivileged(boolean privilegedValue) {
        return new UrsaHookDef(name, event, source, enabled, description, timeout,
                tags, yamlBody, createdByUserId, privilegedValue, action);
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
