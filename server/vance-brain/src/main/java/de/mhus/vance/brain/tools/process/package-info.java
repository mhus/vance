/**
 * Server-side tools for think-process management. The LLM uses these
 * to spawn sub-processes ({@code process_create}), drive them
 * ({@code process_steer}), inspect siblings ({@code process_list},
 * {@code process_status}). Counterpart to the
 * {@code de.mhus.vance.foot.tools.*} client tools — but on purpose
 * <em>not</em> on the client side: spawning LLM-driven engines is a
 * brain concern.
 */
@NullMarked
package de.mhus.vance.brain.tools.process;

import org.jspecify.annotations.NullMarked;
