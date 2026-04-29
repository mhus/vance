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
     * Positional argument specification used by tab-completion. Default
     * is empty — the command takes no arguments, or its arguments are
     * free-form and don't benefit from completion. Override to return a
     * non-empty list when concrete suggestions help the user (closed
     * enum, dynamic source like a process name).
     *
     * <p>Positional: {@code argSpec.get(0)} describes the first token
     * after the command name. When the user has typed past the last
     * described position the completer falls back to the last spec's
     * kind, which models variadic tails (e.g. a trailing chat message).
     */
    default List<ArgSpec> argSpec() {
        return List.of();
    }

    /**
     * Executes the command. {@code args} contains the tokens after the command
     * name, already split on whitespace.
     */
    void execute(List<String> args) throws Exception;
}
