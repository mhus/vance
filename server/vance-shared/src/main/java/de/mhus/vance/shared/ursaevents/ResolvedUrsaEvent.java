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
        /** Inline bearer literal — exclusive with {@link #tokenSettingKey}. {@code null} → no auth or setting-based. */
        @Nullable String tokenLiteral,
        /** Setting key resolved via the cascade — exclusive with {@link #tokenLiteral}. */
        @Nullable String tokenSettingKey,
        /** Static params passed into the spawned target. */
        Map<String, Object> params,
        @Nullable String runAs,
        List<String> tags) {

    /** {@code true} when bearer authentication is required. */
    public boolean requiresAuth() {
        return tokenLiteral != null || tokenSettingKey != null;
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
