package de.mhus.vance.brain.recipe;

/**
 * The hook point a guard script is evaluated at — the Shooty guard system's
 * trigger model. {@code STOP}/{@code TERMINATE} are the classic completion
 * yield points (engine produced its output / explicit terminate);
 * {@code START} fires once per genuine user turn, {@code COMMAND} gates
 * engine-command dispatch. See {@code planning/shooty.md} §2.
 */
public enum GuardPoint {

    /** Fire once per genuine user turn, after the guard-budget reset. */
    START,

    /** Gate an engine command before its handler runs (fail-closed). */
    COMMAND,

    /** Fire on a natural stop (engine produced its output and would yield). */
    STOP,

    /** Fire on an explicit terminate (e.g. Frankie's {@code _terminate}). */
    TERMINATE,

    /** Fire on either stop or terminate (legacy alias for the yield pair). */
    BOTH;

    public boolean firesOnNaturalStop() {
        return this == STOP || this == BOTH;
    }

    public boolean firesOnTerminate() {
        return this == TERMINATE || this == BOTH;
    }

    public boolean firesOnStart() {
        return this == START;
    }

    public boolean firesOnCommand() {
        return this == COMMAND;
    }
}
