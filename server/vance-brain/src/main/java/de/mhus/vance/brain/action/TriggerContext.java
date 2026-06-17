package de.mhus.vance.brain.action;

import org.jspecify.annotations.Nullable;

/**
 * Scope/identity information the trigger surfaces to the executor. All
 * triggers carry tenant + project; everything else depends on the
 * trigger kind.
 *
 * <p>Sealed hierarchy with two variants so the
 * {@link de.mhus.vance.api.action.TriggerAction}-pipeline can express
 * compile-time which spawn surfaces need a session:
 *
 * <ul>
 *   <li>{@link Sessioned} — has a guaranteed-non-null
 *       {@link Sessioned#parentSessionId()}. Required for recipe-action
 *       spawns ({@link SpawnActionExecutor}) which create a
 *       ThinkProcess inside a session.</li>
 *   <li>{@link Standalone} — no enclosing session. Used for script
 *       actions ({@link ScriptActionExecutor}) and workflow-run starts
 *       ({@link WorkflowActionExecutor}); both keep their own scope
 *       inside the executor.</li>
 * </ul>
 *
 * <p>The split replaces the previous single record with a
 * {@code @Nullable parentSessionId} field, which let callers
 * accidentally pass a session-less context to the spawn executor and
 * blow up at runtime. The executor now pattern-matches on
 * {@link Sessioned} at the top of {@code execute(...)}; non-sessioned
 * contexts are rejected with a structured failure result instead of
 * being discovered halfway through the spawn pipeline.
 *
 * <p>{@link #correlationId()} is the id that lands in the event-log
 * row for this run — the executor uses it for downstream logs and
 * (where relevant) writes it onto the spawned Process / Workflow-Run.
 *
 * <p>{@link #sourceTag()} is the trigger-specific tag (e.g.
 * {@code "scheduler:morning-briefing"}, {@code "event:github-pr"},
 * {@code "hook:process.completed:notify-slack"},
 * {@code "workflow:<runId>:plan"}); the executor logs it but does not
 * interpret it.
 */
public sealed interface TriggerContext
        permits TriggerContext.Standalone, TriggerContext.Sessioned {

    String tenantId();

    String projectId();

    /**
     * User identity under which the action runs. Trigger surfaces set
     * this from their per-surface convention (e.g. scheduler's
     * {@code createdBy}, hook's {@code createdByUserId}, tool's caller).
     */
    @Nullable String resolvedRunAs();

    /** Correlation-id shared by all event-log rows for this run. */
    @Nullable String correlationId();

    /** Trigger-specific source tag for the event-log. */
    @Nullable String sourceTag();

    /** Parent ThinkProcess id when the action is spawned from inside one. */
    @Nullable String parentProcessId();

    /**
     * Optional enclosing session — non-null for {@link Sessioned}, always
     * {@code null} for {@link Standalone}. Consumers that need it
     * (notably {@link SpawnActionExecutor}) must pattern-match on
     * {@link Sessioned} for the compile-time guarantee; consumers that
     * merely propagate the value to logs / tool-invocation contexts
     * (script / workflow executors) read it via this nullable getter.
     */
    @Nullable String parentSessionId();

    // ──────────────────── Factory helpers ────────────────────

    /**
     * Build a sessioned context — required for any
     * {@link de.mhus.vance.api.action.TriggerAction.Recipe} spawn.
     * {@code parentSessionId} must be non-blank; for trigger surfaces
     * without a natural session (scheduler tick, lifecycle hook, REST
     * webhook firing a recipe) the convention is to lazily create a
     * per-trigger system session via {@code SessionService.findSystemSession}.
     */
    static Sessioned sessioned(
            String tenantId,
            String projectId,
            @Nullable String resolvedRunAs,
            @Nullable String correlationId,
            @Nullable String sourceTag,
            String parentSessionId,
            @Nullable String parentProcessId) {
        return new Sessioned(tenantId, projectId, resolvedRunAs,
                correlationId, sourceTag, parentSessionId, parentProcessId);
    }

    /**
     * Build a standalone context — used by script and workflow
     * actions, which do not need a session. Also the only valid choice
     * for trigger surfaces that fire non-recipe actions.
     */
    static Standalone standalone(
            String tenantId,
            String projectId,
            @Nullable String resolvedRunAs,
            @Nullable String correlationId,
            @Nullable String sourceTag,
            @Nullable String parentProcessId) {
        return new Standalone(tenantId, projectId, resolvedRunAs,
                correlationId, sourceTag, parentProcessId);
    }

    // ──────────────────── Variants ────────────────────

    /** Trigger context without an enclosing session. */
    record Standalone(
            String tenantId,
            String projectId,
            @Nullable String resolvedRunAs,
            @Nullable String correlationId,
            @Nullable String sourceTag,
            @Nullable String parentProcessId) implements TriggerContext {

        public Standalone {
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException(
                        "TriggerContext.tenantId must be non-blank");
            }
            if (projectId == null || projectId.isBlank()) {
                throw new IllegalArgumentException(
                        "TriggerContext.projectId must be non-blank");
            }
        }

        /** Always {@code null} — Standalone has no session by definition. */
        @Override
        public @Nullable String parentSessionId() {
            return null;
        }
    }

    /**
     * Trigger context with a guaranteed-non-null session — required
     * by {@link SpawnActionExecutor} for recipe-action spawns.
     */
    record Sessioned(
            String tenantId,
            String projectId,
            @Nullable String resolvedRunAs,
            @Nullable String correlationId,
            @Nullable String sourceTag,
            String parentSessionId,
            @Nullable String parentProcessId) implements TriggerContext {

        public Sessioned {
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException(
                        "TriggerContext.tenantId must be non-blank");
            }
            if (projectId == null || projectId.isBlank()) {
                throw new IllegalArgumentException(
                        "TriggerContext.projectId must be non-blank");
            }
            if (parentSessionId == null || parentSessionId.isBlank()) {
                throw new IllegalArgumentException(
                        "TriggerContext.Sessioned.parentSessionId must be non-blank");
            }
        }
    }
}
