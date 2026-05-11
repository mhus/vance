package de.mhus.vance.brain.thinkengine.plan;

import java.util.Set;

/**
 * Action-type + param-key constants every Plan-Mode-capable engine
 * shares. Today Arthur owns these via {@code ArthurActionSchema};
 * after the extraction (see {@code planning/plan-mode-shared.md})
 * each engine schema references the constants here, and the
 * {@link PlanModeService} dispatches them uniformly.
 *
 * <p>Per-mode allow-sets stay on the engine schemas — what's "valid in
 * EXPLORING" depends on the engine's specific vocabulary (which other
 * non-Plan actions are alive). Only the names + payload-keys are shared
 * here.
 */
public final class PlanModeActionSchema {

    private PlanModeActionSchema() {}

    // ── Action types ────────────────────────────────────────────────

    /** Switch into {@code EXPLORING} for plan-then-confirm-then-execute. */
    public static final String TYPE_START_PLAN      = "START_PLAN";

    /** Submit plan markdown + TodoList for user approval; flip to {@code PLANNING}. */
    public static final String TYPE_PROPOSE_PLAN    = "PROPOSE_PLAN";

    /** User accepted the plan; flip to {@code EXECUTING}. */
    public static final String TYPE_START_EXECUTION = "START_EXECUTION";

    /** Mark one or more TodoItems IN_PROGRESS / COMPLETED. */
    public static final String TYPE_TODO_UPDATE     = "TODO_UPDATE";

    /** All Plan-Mode action types — for {@link #isPlanModeAction} and
     *  engine schemas that want to union them into their own ALL_TYPES set. */
    public static final Set<String> ALL_TYPES = Set.of(
            TYPE_START_PLAN, TYPE_PROPOSE_PLAN, TYPE_START_EXECUTION, TYPE_TODO_UPDATE);

    public static boolean isPlanModeAction(String type) {
        return type != null && ALL_TYPES.contains(type);
    }

    // ── Param keys ──────────────────────────────────────────────────

    /** {@code START_PLAN} — optional one-liner restating the task. */
    public static final String PARAM_GOAL    = "goal";

    /** {@code PROPOSE_PLAN} — Markdown plan body shown to the user. */
    public static final String PARAM_PLAN    = "plan";

    /** {@code PROPOSE_PLAN} — one-line summary for spinner / log / inbox. */
    public static final String PARAM_SUMMARY = "summary";

    /** {@code PROPOSE_PLAN} — array of TodoItems (3-8 entries). */
    public static final String PARAM_TODOS   = "todos";

    /** {@code START_EXECUTION} — optional extra context from the user's approval. */
    public static final String PARAM_NOTES   = "notes";

    /** {@code TODO_UPDATE} — array of {@code {id, status}} updates. */
    public static final String PARAM_UPDATES = "updates";

    // ── Process-engine-params (recipe-driven) ───────────────────────

    /** Recipe param. {@code auto} (default) / {@code required} / {@code disabled}. */
    public static final String ENGINE_PARAM_PLAN_MODE = "planMode";
    public static final String PLAN_MODE_AUTO     = "auto";
    public static final String PLAN_MODE_REQUIRED = "required";
    public static final String PLAN_MODE_DISABLED = "disabled";
}
