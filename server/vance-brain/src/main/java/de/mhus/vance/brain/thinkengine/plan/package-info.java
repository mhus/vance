/**
 * Shared Plan-Mode layer — action-schema constants + handler service.
 * Engines that support the plan-explore-execute pattern (Arthur today,
 * Eddie tomorrow) dispatch the four Plan-Mode action types through
 * {@link de.mhus.vance.brain.thinkengine.plan.PlanModeService} instead
 * of duplicating handler code per engine.
 *
 * <p>See {@code planning/plan-mode-shared.md} for the extraction
 * design and {@code specification/plan-mode.md} for the user-facing
 * spec.
 */
@NullMarked
package de.mhus.vance.brain.thinkengine.plan;

import org.jspecify.annotations.NullMarked;
