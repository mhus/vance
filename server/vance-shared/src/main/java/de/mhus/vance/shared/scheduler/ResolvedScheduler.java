package de.mhus.vance.shared.scheduler;

import de.mhus.vance.api.action.ScriptSource;
import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.api.scheduler.LockMode;
import de.mhus.vance.api.scheduler.OverlapPolicy;
import de.mhus.vance.api.scheduler.SchedulerSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Result of loading and parsing one scheduler YAML document.
 *
 * <p>{@code name} is taken from the document path (not the YAML body),
 * matching the recipe loader convention. The {@code yaml} field carries
 * the raw body so callers (REST GET, scheduler tool {@code _get}) can
 * round-trip it without re-serialisation drift. {@code createdBy} comes
 * from the underlying {@link de.mhus.vance.shared.document.DocumentDocument}
 * and is the fallback for {@link #runAs} when the YAML doesn't pin it.
 *
 * <p>Trigger target: exactly one of {@link #recipe}, {@link #workflow},
 * {@link #script} is set — enforced by {@code SchedulerLoader}. Callers
 * that want a unified action shape use {@link #toTriggerAction()}.
 *
 * <p>{@code documentId} is the Mongo {@code _id} of the document and
 * is needed to call {@link de.mhus.vance.shared.document.DocumentService#update}
 * from the scheduler tools / REST endpoints. {@code null} for resource-
 * layer hits — schedulers don't ship a resource tier so this stays
 * informational.
 */
public record ResolvedScheduler(
        String name,
        String yaml,
        SchedulerSource source,
        @Nullable String documentId,
        @Nullable String createdBy,
        String description,
        /** Quartz-cron expression for recurring triggers; {@code null} when {@link #at} is set. */
        @Nullable String cron,
        /** One-shot fire instant; {@code null} when {@link #cron} is set. See {@code specification/scheduler.md} §10a. */
        @Nullable Instant at,
        @Nullable String timezone,
        boolean enabled,
        /** Recipe name to spawn — mutually exclusive with {@link #workflow} and {@link #script}. */
        @Nullable String recipe,
        /** Workflow name to spawn — mutually exclusive with {@link #recipe} and {@link #script}. See {@code specification/workflows.md}. */
        @Nullable String workflow,
        /** Script spec — mutually exclusive with {@link #recipe} and {@link #workflow}. See {@code planning/trigger-actions.md}. */
        @Nullable ScriptSpec script,
        Map<String, Object> params,
        @Nullable String initialMessage,
        @Nullable String runAs,
        OverlapPolicy overlap,
        LockMode lockMode,
        List<String> tags) {

    /**
     * Inline script-source spec for {@code script:}-based scheduler
     * triggers. Mirrors the script side of
     * {@link TriggerAction.Script} so the loader can persist it
     * pre-validated.
     */
    public record ScriptSpec(
            ScriptSource source,
            @Nullable String dirName,
            String path,
            @Nullable Integer timeoutSeconds) {
    }

    /** {@code true} when this scheduler spawns a Hactar workflow run rather than a ThinkProcess. */
    public boolean isWorkflowTrigger() {
        return workflow != null && !workflow.isBlank();
    }

    /** {@code true} when this scheduler runs a script rather than spawning a process or workflow. */
    public boolean isScriptTrigger() {
        return script != null;
    }

    /**
     * Build the unified {@link TriggerAction} for this scheduler. The
     * {@code recipe}/{@code workflow}/{@code script} disjunction is
     * enforced at load time; this method assumes the invariant.
     *
     * @throws IllegalStateException when none of the three fields is set
     *         (defensive — should never happen for a parsed ResolvedScheduler).
     */
    public TriggerAction toTriggerAction() {
        if (recipe != null && !recipe.isBlank()) {
            return new TriggerAction.Recipe(recipe, initialMessage, params, effectiveRunAs());
        }
        if (workflow != null && !workflow.isBlank()) {
            return new TriggerAction.Workflow(workflow, params, effectiveRunAs());
        }
        if (script != null) {
            return new TriggerAction.Script(
                    script.source(),
                    script.dirName(),
                    script.path(),
                    script.timeoutSeconds(),
                    params,
                    effectiveRunAs());
        }
        throw new IllegalStateException(
                "scheduler '" + name + "' has no trigger target (recipe / workflow / script)");
    }

    /**
     * {@code runAs} after applying the fallback rule "default to
     * {@link #createdBy} when the YAML didn't pin it". Never blank —
     * callers can pass the result straight to session/process creation.
     */
    public @Nullable String effectiveRunAs() {
        if (runAs != null && !runAs.isBlank()) return runAs;
        if (createdBy != null && !createdBy.isBlank()) return createdBy;
        return null;
    }

    /** {@code true} when the scheduler is a one-shot ({@code at:}-driven). */
    public boolean isOneShot() {
        return at != null;
    }

    /** {@code true} when LLM tools must refuse mutation on this entry. */
    public boolean isLlmLocked() {
        return lockMode != LockMode.FULL;
    }

    /** {@code true} when LLM read tools must hide this entry. */
    public boolean isLlmHidden() {
        return lockMode == LockMode.HIDDEN;
    }
}
