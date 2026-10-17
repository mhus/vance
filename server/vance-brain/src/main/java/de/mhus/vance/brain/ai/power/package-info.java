/**
 * The LLM power switch: an admin turns model access off for a whole tenant or
 * for single users of it.
 *
 * <p>{@link LlmPowerSwitch} holds the state per tenant in RAM and refreshes it
 * in the background, {@link LlmPowerGate} asks it before every model call, and
 * {@link LlmPowerController} is the admin surface behind the Insights tab
 * "Power". Storage is {@code LlmPowerService} in {@code vance-shared}.
 */
@NullMarked
package de.mhus.vance.brain.ai.power;

import org.jspecify.annotations.NullMarked;
