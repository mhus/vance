package de.mhus.vance.brain.guard.handler;

/**
 * A Shooty guard implemented as a Java Spring bean — the typed twin of
 * a guard script. Referenced from the recipe {@code guard:} block by
 * its stable {@link #name()}:
 *
 * <pre>{@code
 * guard:
 *   - handler: fence-check
 *     trigger: stop            # optional narrowing; without it the
 *                              # handler runs at every point
 * }</pre>
 *
 * <p>A handler covers <b>all</b> hook points; the default no-op
 * implementations stand in for the ones it does not need. Judge and
 * action stay imperative in the handler method — a handler that finds
 * a problem calls {@link GuardContext#continueWith(String)} (STOP/
 * TERMINATE), {@link GuardContext#deny(String)} (COMMAND) or one of
 * the other point actions, exactly like {@code vance.guard.*} in a
 * script.
 *
 * <p>Handlers run through the same evaluation machinery as scripts:
 * per-point fail strategy (fail-open at START/STOP, fail-closed at
 * COMMAND), cap-aware {@code continueWith} against the process's
 * {@code guardRounds} counter, the shared per-process / per-session
 * scratch stores, and the re-entrancy marker (a handler does not guard
 * its own actions).
 *
 * <p>Addons contribute handlers by registering beans — the
 * {@link GuardHandlerRegistry} collects every {@code GuardHandler} in
 * the context and fails the boot on duplicate names.
 */
public interface GuardHandler {

    /**
     * The stable name a recipe references as {@code handler:}. Keep
     * it kebab-case and immutable once shipped — recipes depend on it.
     */
    String name();

    /**
     * One-line description shown in unknown-name errors and
     * {@code //guard get} listings. English, LLM-/operator-facing.
     */
    default String description() {
        return "";
    }

    /**
     * The START hook — fires once per genuine user turn, after the
     * guard-budget reset. Typical actions:
     * {@link GuardContext#activateSkill(String, String)} and
     * {@link GuardContext#setTurnPrompt(String)}.
     */
    default void onStart(GuardContext ctx) {}

    /**
     * The COMMAND hook — gates an engine command before its handler
     * runs. Fail-closed: a handler error (or {@code deny}) fails the
     * command hard.
     */
    default void onCommand(GuardContext ctx) {}

    /**
     * The STOP hook — fires when the engine produced its final output
     * and would yield. Typical action: cap-aware
     * {@link GuardContext#continueWith(String)} with a follow-up.
     */
    default void onStop(GuardContext ctx) {}

    /**
     * The TERMINATE hook — fires on an explicit terminate (e.g.
     * Frankie's {@code _terminate}).
     */
    default void onTerminate(GuardContext ctx) {}
}
