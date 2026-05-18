/**
 * Executor layer for the unified
 * {@link de.mhus.vance.api.action.TriggerAction} model. One executor
 * per action variant ({@code Recipe}, {@code Script}, {@code Workflow});
 * triggers (scheduler / event / workflow-task / LLM-tool / manual)
 * delegate via {@link de.mhus.vance.brain.action.ActionExecutorRegistry}.
 *
 * <p>Layered design: parsing and validation happen in
 * {@code vance-shared} (one place, all triggers); the executor layer
 * here glues the resolved action onto the existing service stack
 * ({@code ThinkProcessService}, {@code HactarRunService},
 * {@code ScriptExecutor}, …).
 *
 * <p>Design rationale: {@code planning/trigger-actions.md} §5.3.
 */
@NullMarked
package de.mhus.vance.brain.action;

import org.jspecify.annotations.NullMarked;
