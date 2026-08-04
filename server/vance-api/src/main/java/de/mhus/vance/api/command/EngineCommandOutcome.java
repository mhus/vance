package de.mhus.vance.api.command;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Result classification of a control-plane engine command dispatched
 * over {@code process-command}. See {@code planning/engine-commands.md} §2.
 */
@GenerateTypeScript("command")
public enum EngineCommandOutcome {

    /** A handler processed the command. */
    OK,

    /**
     * No handler is registered for the command's verb — a defined
     * no-op, not an error (the channel is generic; the verb vocabulary
     * grows per engine). See {@code planning/engine-commands.md} §2.4.
     */
    UNKNOWN,

    /** A handler was found but failed while processing the command. */
    ERROR
}
