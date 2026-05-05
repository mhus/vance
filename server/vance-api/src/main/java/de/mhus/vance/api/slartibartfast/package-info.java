/**
 * Wire-contract types for the Slartibartfast plan-architect engine.
 * Evidence-based planning workflow with hard validation gates —
 * every plan element traces back to a source, every constraint is
 * justified by a subgoal, no LLM "should-work-out" mush.
 *
 * <p>The {@link ArchitectState} aggregates the full audit chain
 * persisted on {@code ThinkProcessDocument.engineParams.architectState}.
 * See {@code specification/slartibartfast-engine.md} for the full spec.
 */
@NullMarked
package de.mhus.vance.api.slartibartfast;

import org.jspecify.annotations.NullMarked;
