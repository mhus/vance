/**
 * Unified trigger-action model. A {@link de.mhus.vance.api.action.TriggerAction}
 * describes what a trigger (scheduler, event, workflow-task, LLM tool,
 * manual REST call) is supposed to spawn or execute. Same shape across
 * all triggers, parsed once, dispatched by an executor.
 *
 * <p>See {@code planning/trigger-actions.md} for the design rationale
 * and {@code specification/trigger-actions.md} (TODO) for the
 * normative spec.
 */
@NullMarked
package de.mhus.vance.api.action;

import org.jspecify.annotations.NullMarked;
