package de.mhus.vance.cli.chat.commands;

import java.util.List;

/**
 * A single slash-command. Covers both purely local actions (e.g. {@code /clear})
 * and server-bound actions (e.g. {@code /ping}). The command decides itself
 * whether to touch {@link CommandContext#connection()} or not.
 */
public interface Command {

    /** Primary name without the leading slash, lowercase. */
    String name();

    /** Optional additional trigger names without the leading slash, lowercase. */
    default List<String> aliases() {
        return List.of();
    }

    /** Short human-readable description shown by {@code /help}. */
    String description();

    /** Usage signature for {@code /help}, e.g. {@code /connect [tenant] [user]}. */
    String usage();

    /** Execute the command. {@code args} is the raw argument tail split on whitespace. */
    void execute(CommandContext ctx, String[] args);
}
