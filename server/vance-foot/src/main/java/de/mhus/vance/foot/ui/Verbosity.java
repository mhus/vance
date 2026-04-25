package de.mhus.vance.foot.ui;

/**
 * Level of detail for user-facing terminal output. Ordered from most important
 * to most detailed; {@link ChatTerminal} prints a message only when its level
 * is {@code <=} the current threshold.
 */
public enum Verbosity {
    ERROR,
    WARN,
    INFO,
    VERBOSE,
    DEBUG,
    TRACE;

    /** {@code true} if {@code messageLevel} should be shown when threshold is {@code current}. */
    public boolean shows(Verbosity messageLevel) {
        return messageLevel.ordinal() <= this.ordinal();
    }
}
