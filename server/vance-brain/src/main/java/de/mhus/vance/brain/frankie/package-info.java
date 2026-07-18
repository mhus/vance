/**
 * Frankie — the focused-worker think-engine (Pi-style loop).
 *
 * <p>Frankie is the generic executor: it runs a task in multiple
 * turns until it's done. Stop conditions are hardcoded — natural stop
 * (LLM produces no tool calls), tool-driven terminate
 * ({@code _terminate: true} in a tool result), external interrupt
 * (process status set to {@code STOPPED} / {@code SUSPENDED} from the
 * outside via {@code ProcessStopTool} / {@code ProcessPauseTool} / UI),
 * or safety nets (wallclock timeout, idle-stuck detection). No
 * {@code maxIterations} cap — Arthur idea, doesn't fit a focused worker.
 *
 * <p>First validating recipe is {@code coding} (see
 * {@code planning/coding-recipe.md}). Later variants
 * ({@code frankie-fook-upstream}, {@code frankie-repair}, …) are
 * recipes on the same engine, not new engine code.
 *
 * <p>See {@code planning/frankie-engine.md} for the design.
 */
@NullMarked
package de.mhus.vance.brain.frankie;

import org.jspecify.annotations.NullMarked;
