package de.mhus.vance.shared.ursaevents;

import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.api.ursaevents.EventSource;
import de.mhus.vance.shared.ursascheduler.ResolvedUrsaScheduler;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Result of loading and parsing one event YAML document.
 *
 * <p>Trigger target: exactly one of {@link #recipe}, {@link #workflow},
 * {@link #script} is set — enforced by {@code UrsaEventLoader}. Callers
 * use {@link #toTriggerAction(Map)} to obtain the unified
 * {@link TriggerAction} with the incoming HTTP payload merged into the
 * params under the key {@code payload}.
 *
 * <p>Authentication is <strong>mandatory</strong>: exactly one of
 * {@link #tokenLiteral}, {@link #tokenSettingKey} or {@link #authPublic}
 * must be set. An event that is meant to be open declares it with
 * {@code auth.public: true} rather than by omitting the block — the
 * unsafe case must be the stated one, not the silent one.
 *
 * <p>Bearer secrets are kept as <strong>references</strong>, not values:
 * {@link #tokenLiteral} carries an inline {@code auth.token:} when set
 * (cheap convenience for tests), {@link #tokenSettingKey} carries the
 * setting-cascade key when the YAML used {@code auth.tokenSetting:}.
 * The actual secret comparison happens in {@code UrsaEventService} which
 * resolves the setting via {@link de.mhus.vance.shared.settings.SettingService}.
 */
public record ResolvedUrsaEvent(
        String name,
        String yaml,
        EventSource source,
        @Nullable String documentId,
        @Nullable String createdBy,
        @Nullable String description,
        /** Recipe to spawn — mutually exclusive with {@link #workflow} and {@link #script}. */
        @Nullable String recipe,
        /** Workflow to spawn — mutually exclusive with {@link #recipe} and {@link #script}. */
        @Nullable String workflow,
        /** Script to run — mutually exclusive with {@link #recipe} and {@link #workflow}. */
        ResolvedUrsaScheduler.@Nullable ScriptSpec script,
        /** First user message dispatched to a recipe-spawned process. {@code null} for workflow/script. */
        @Nullable String initialMessage,
        /** {@code false} disables the event — REST returns 404. */
        boolean enabled,
        /** Upper-case HTTP methods that may trigger this event ({@code GET}, {@code POST}). Empty = both. */
        Set<String> methods,
        /** Inline bearer literal — exclusive with {@link #tokenSettingKey}. */
        @Nullable String tokenLiteral,
        /** Setting key resolved via the cascade — exclusive with {@link #tokenLiteral}. */
        @Nullable String tokenSettingKey,
        /**
         * {@code auth.public: true} — the event is deliberately open.
         * Exclusive with both token variants; exactly one of the three is
         * required, see {@code UrsaEventLoader}.
         */
        boolean authPublic,
        /** Static params passed into the spawned target. */
        Map<String, Object> params,
        @Nullable String runAs,
        /**
         * Explicit {@code async:} from the YAML. {@code null} means "not
         * stated" — {@link #resolvedAsync()} then derives it from the
         * action variant.
         */
        @Nullable Boolean async,
        /**
         * Explicit {@code outputToAgents:} from the YAML. {@code null}
         * means "not stated" — see {@link #outputVisibleToAgents()}.
         */
        @Nullable Boolean outputToAgents,
        List<String> tags) {

    /** {@code true} when bearer authentication is required. */
    public boolean requiresAuth() {
        return tokenLiteral != null || tokenSettingKey != null;
    }

    /**
     * Whether the trigger returns before the action has finished.
     *
     * <p>Unstated defaults to what the action variant can actually do: a
     * script runs to completion within a bounded time and can therefore
     * answer synchronously, while a recipe- or workflow-spawn is
     * open-ended and only ever reports that it started. The loader rejects
     * {@code async: false} on those two, so the derived value and an
     * explicit one never disagree.
     */
    public boolean resolvedAsync() {
        return async != null ? async : script == null;
    }

    /**
     * Whether {@code event_fire} — the agent-facing trigger, which skips
     * the bearer check by design — may see this event's output.
     *
     * <p>Unstated follows the privilege boundary rather than a convention.
     * Without {@code runAs} the action runs as the event's own owner and
     * returns data an agent in the same project could reach anyway, so
     * there is nothing to withhold. With {@code runAs} the action crosses
     * into another identity while the caller has not authenticated at all
     * — then the operator has to say so explicitly.
     *
     * <p>Note this reads the raw {@link #runAs} field, not
     * {@link #effectiveRunAs()}: falling back to the document's
     * {@code createdBy} is not an identity crossing.
     */
    public boolean outputVisibleToAgents() {
        return outputToAgents != null ? outputToAgents : runAs == null;
    }

    /** {@code true} when the given HTTP method is accepted by this event. */
    public boolean acceptsMethod(String method) {
        if (methods.isEmpty()) return true;
        return methods.contains(method.toUpperCase(java.util.Locale.ROOT));
    }

    /** Effective {@code runAs} — same fallback chain as {@code ResolvedUrsaScheduler}. */
    @Nullable
    public String effectiveRunAs() {
        if (runAs != null && !runAs.isBlank()) return runAs;
        if (createdBy != null && !createdBy.isBlank()) return createdBy;
        return null;
    }

    /**
     * Build the unified {@link TriggerAction} for this event. The
     * {@code mergedParams} should already include the incoming HTTP
     * payload under the {@code payload} key (see
     * {@code specification/events.md} §4).
     */
    public TriggerAction toTriggerAction(Map<String, Object> mergedParams) {
        if (recipe != null && !recipe.isBlank()) {
            return TriggerAction.Recipe.of(recipe, initialMessage, mergedParams, effectiveRunAs());
        }
        if (workflow != null && !workflow.isBlank()) {
            return new TriggerAction.Workflow(workflow, mergedParams, effectiveRunAs());
        }
        if (script != null) {
            return new TriggerAction.Script(
                    script.source(),
                    script.dirName(),
                    script.path(),
                    script.timeoutSeconds(),
                    mergedParams,
                    effectiveRunAs());
        }
        throw new IllegalStateException(
                "event '" + name + "' has no trigger target");
    }
}
