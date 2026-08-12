/**
 * Tool-surface budget: keeps the per-turn tool manifest inside the
 * hard {@code tools}-array limit that OpenAI-wire endpoints enforce.
 *
 * <p>Entry points:
 * <ul>
 *   <li>{@link de.mhus.vance.brain.tools.budget.ToolBudgetService} —
 *       resolves the effective limit for a process (minimum over the
 *       whole fallback chain) and collects the ranking signals.</li>
 *   <li>{@link de.mhus.vance.brain.tools.budget.ToolTriage} — pure
 *       function that demotes whole tool families from primary to
 *       deferred until the surface fits.</li>
 *   <li>{@link de.mhus.vance.brain.tools.budget.ObservedToolLimitRegistry}
 *       — learns the real limit from a provider rejection so a stale
 *       catalog value self-corrects on the next turn.</li>
 * </ul>
 *
 * <p>Design: {@code planning/tool-surface-budget.md}.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.brain.tools.budget;
