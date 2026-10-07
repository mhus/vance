package de.mhus.vance.brain.recipe;

/**
 * The hook point a guard script is evaluated at — the Shooty guard system's
 * trigger model. {@code STOP}/{@code TERMINATE} are the classic completion
 * yield points (engine produced its output / explicit terminate);
 * {@code START} fires once per genuine user turn, {@code COMMAND} gates
 * engine-command dispatch, {@code TOOL} gates exec-run tool calls before
 * they execute. See {@code specification/public/shooty.md} §2.
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

    /**
     * Gate an exec-run tool call ({@code exec_run} and its
     * {@code work_}/{@code client_} backends) before it executes —
     * fail-closed like COMMAND. The v3.2 scope is exec-run only;
     * other tool families may join later.
     */
    TOOL,

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

    public boolean firesOnTool() {
        return this == TOOL;
    }
}
