package de.mhus.vance.foot.command;

import java.util.List;

/**
 * One slash command exposed inside the REPL. Implementations are registered
 * automatically via Spring component scan and looked up by {@link #name()}.
 */
public interface SlashCommand {

    /** Lower-case name without the leading slash (e.g. {@code "help"} for {@code /help}). */
    String name();

    /** One-line description shown by {@code /help}. */
    String description();

    /**
     * Executes the command. {@code args} contains the tokens after the command
     * name, already split on whitespace.
     */
    void execute(List<String> args) throws Exception;
}
