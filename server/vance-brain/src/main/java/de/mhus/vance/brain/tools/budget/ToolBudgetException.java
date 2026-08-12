package de.mhus.vance.brain.tools.budget;

/**
 * The configured tool cap cannot hold even the mandatory floor
 * ({@code tool_list} / {@code tool_description}) plus the reservation for
 * the engine's action tool.
 *
 * <p>Thrown instead of silently dropping the floor: a surface without the
 * discovery pair makes every deferred tool unreachable, and the model
 * reports "I can't do that" for capabilities that are right there. That
 * failure mode is invisible in logs, so a misconfigured
 * {@code maxTools} has to be loud.
 */
public class ToolBudgetException extends RuntimeException {

    public ToolBudgetException(String message) {
        super(message);
    }
}
