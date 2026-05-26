/**
 * Central helper for single-shot LLM calls with a Recipe as config
 * profile — no process spawn, no lane lock, no chat history. See
 * {@code specification/light-llm-service.md} for the full design.
 *
 * <p>First consumer: {@code DiscoveryService} (the backend of the
 * {@code how_do_i} discovery tool). Additional future consumers
 * (title generation, intent classification, summary generation) will
 * use the same {@link LightLlmService} API without re-implementing
 * the recipe-resolve + pebble-render + schema-loop pattern.
 *
 * <p>Recipes used here MUST be marked {@code internal: true} so the
 * standard {@code RecipeSelectorService} doesn't accidentally offer
 * them as candidates to the LLM-driven DELEGATE selector.
 */
@NullMarked
package de.mhus.vance.brain.ai.light;

import org.jspecify.annotations.NullMarked;
