package de.mhus.vance.foot.command;

import java.util.List;

/**
 * Describes one positional argument of a slash command. The list of
 * specs returned by {@link SlashCommand#argSpec()} is positional —
 * {@code argSpec.get(0)} describes the first token after the command
 * name, etc. Variadic tails (e.g. a trailing free-form chat message)
 * are expressed by repeating the last spec implicitly: when the user
 * has typed past the last described position, the completer falls back
 * to the last spec's kind.
 *
 * @param name    short human label, used as the placeholder hint shown
 *                when no concrete completion is available (e.g.
 *                {@code <name>}, {@code <message>})
 * @param kind    completion source for this position
 * @param choices closed list of values when {@code kind} is
 *                {@link ArgKind#ENUM}; ignored otherwise
 */
public record ArgSpec(String name, ArgKind kind, List<String> choices) {

    public ArgSpec {
        choices = choices == null ? List.of() : List.copyOf(choices);
    }

    public static ArgSpec free(String name) {
        return new ArgSpec(name, ArgKind.FREE, List.of());
    }

    public static ArgSpec enumOf(String name, List<String> choices) {
        return new ArgSpec(name, ArgKind.ENUM, choices);
    }

    public static ArgSpec of(String name, ArgKind kind) {
        return new ArgSpec(name, kind, List.of());
    }
}
