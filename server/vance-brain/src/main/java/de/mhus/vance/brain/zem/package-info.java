/**
 * Zem — the System-One decision engine. {@link
 * de.mhus.vance.brain.zem.ZemService} is the one entry point: it resolves
 * a profile (a recipe with {@code engine: zem}) or inline questions,
 * resolves and validates the model, throttles, retries and books usage.
 * Tools, the script API, the spawnable engine and (v2) the Wowbagger
 * pool all call it.
 *
 * <p>Design: {@code planning/zem-engine.md}.
 */
@NullMarked
package de.mhus.vance.brain.zem;

import org.jspecify.annotations.NullMarked;
