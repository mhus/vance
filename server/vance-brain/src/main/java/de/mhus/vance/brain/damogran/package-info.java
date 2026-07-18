/**
 * Damogran — the workspace compose system.
 *
 * <p>A lightweight, linear batch runner over a named workspace: provision a
 * workspace, import documents into it, run a sequence of tasks
 * (exec / js / python / spawn / llm / domain tasks such as tex), and export
 * results back to documents. The character is work-in-progress: test, re-run
 * single tasks, let an LLM inspect the result, iterate — all within a working
 * session, not over days.
 *
 * <p>Damogran sits <em>below</em> the orchestrators (Vogon, Magrathea) and
 * <em>above</em> the shared execution layer ({@code ActionExecutorRegistry} /
 * {@code TriggerAction}, {@code LightLlmService}, {@code WorkspaceService},
 * {@code WorkTargetDispatcher}). It adds the workspace dimension neither Vogon
 * nor Magrathea has as a first-class concept, and reuses the shared layer for
 * step execution — it never invents parallel executors.
 *
 * <p>The name: Damogran is the remote, mostly-uninhabited planet where the
 * {@code Heart of Gold} was secretly built and launched — a transient,
 * isolated build-and-launch site. Convention: a place-name (Magrathea,
 * Damogran) denotes a brainless infrastructure subsystem; a character-name
 * (Arthur, Ford, Marvin, Vogon, …) denotes an LLM engine. Damogran
 * <em>calls</em> LLMs (as a task type) but <em>is</em> not one.
 *
 * <p>Design notes: {@code planning/damogran-system.md}.
 */
@NullMarked
package de.mhus.vance.brain.damogran;

import org.jspecify.annotations.NullMarked;
