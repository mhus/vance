/**
 * Engine-intrinsic tools for the Frankie engine. Today: the
 * TodoList-tracking pair {@code todo_write} / {@code todo_update},
 * exposed to every Frankie recipe via
 * {@link de.mhus.vance.brain.frankie.FrankieEngine#allowedTools()}.
 *
 * <p>The tools share persistence with the full Plan-Mode mechanic
 * (Arthur / Eddie) — they delegate to
 * {@link de.mhus.vance.shared.thinkprocess.ThinkProcessService#setTodos}
 * and {@code updateTodoStatuses} and emit the same
 * {@code todos-updated} / {@code plan-proposed} WebSocket
 * notifications through the existing
 * {@link de.mhus.vance.brain.arthur.PlanModeEventEmitter}. No Mode
 * switch, no Approval pipeline — see
 * {@code specification/frankie-engine.md §9} for the reduced
 * variant Frankie uses.
 */
@NullMarked
package de.mhus.vance.brain.frankie.tools;

import org.jspecify.annotations.NullMarked;
