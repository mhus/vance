package de.mhus.vance.brain.tools.budget;

import java.time.Instant;
import java.util.Map;

/**
 * The measurement side of the tool-surface budget: how many tool schemas
 * the request may carry, and the observed signals that order the
 * candidates inside one priority class.
 *
 * <p>Built per turn by {@link ToolBudgetService}. Separate from the
 * <em>declared</em> priority ({@link ToolTriage.Hints}, which comes from
 * the recipe) on purpose: the declared class is the coarse order, the
 * measured signals only break ties inside a class. Sorting across class
 * boundaries by usage would let a popular convenience tool push out a
 * rarely-used but necessary one.
 *
 * @param maxTools          hard cap the endpoint enforces on the
 *                          {@code tools} array. {@code <= 0} means
 *                          "no known limit" — the triage is a no-op.
 * @param reserved          slots held back from {@code maxTools}: the
 *                          engine's own action tool (appended outside
 *                          the classification) plus headroom for
 *                          runtime activations. Without headroom the
 *                          first {@code tool_description} activation
 *                          would blow the limit mid-turn.
 * @param activationRecency {@code toolName → last activation} for this
 *                          process. The strongest signal there is: the
 *                          model reached for it, so the task shape is
 *                          observed rather than guessed.
 * @param usage             {@code toolName → demand count} in this
 *                          project/tenant. Weak signal, ordering only.
 * @param maxActivated      how many activated deferred tools may hold a
 *                          top-class slot. Beyond that, the oldest
 *                          activations compete in the lowest class —
 *                          a long-running process would otherwise fill
 *                          the whole manifest with activations. {@code 0}
 *                          disables the cap.
 */
public record ToolBudget(
        int maxTools,
        int reserved,
        Map<String, Instant> activationRecency,
        Map<String, Long> usage,
        int maxActivated) {

    /** No limit known — {@link ToolTriage} leaves the surface untouched. */
    public static final ToolBudget UNLIMITED = new ToolBudget(0, 0, Map.of(), Map.of(), 0);

    public ToolBudget {
        activationRecency = activationRecency == null ? Map.of() : Map.copyOf(activationRecency);
        usage = usage == null ? Map.of() : Map.copyOf(usage);
        if (reserved < 0) reserved = 0;
        if (maxActivated < 0) maxActivated = 0;
    }

    /**
     * Limit-only budget without measured signals — for callers that know
     * the cap but have no process context (tests, tooling).
     */
    public ToolBudget(int maxTools, int reserved) {
        this(maxTools, reserved, Map.of(), Map.of(), 0);
    }

    /** Is a cap configured at all? */
    public boolean hasLimit() {
        return maxTools > 0;
    }

    /** Slots available for classified tools, after the reservation. */
    public int effectiveLimit() {
        return Math.max(0, maxTools - reserved);
    }
}
