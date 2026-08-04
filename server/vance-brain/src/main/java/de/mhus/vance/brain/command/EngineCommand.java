package de.mhus.vance.brain.command;

import java.util.Map;

/**
 * A generic control-plane function call delivered to a think-process's
 * engine — the internal form of a {@code process-command} frame. The
 * command vocabulary is deliberately open: {@link #name} is routed to an
 * {@link EngineCommandHandler} registered for that exact verb, and an
 * unknown verb is a defined no-op (never a crash).
 *
 * <p>See {@code planning/engine-commands.md} §2.
 *
 * @param name the verb, optionally namespaced ({@code namespace.verb})
 * @param args command arguments; never {@code null} (coerced to empty)
 */
public record EngineCommand(String name, Map<String, Object> args) {

    public EngineCommand {
        args = args == null ? Map.of() : Map.copyOf(args);
    }

    /**
     * Parses the canonical command-string form shared by every author
     * surface (foot {@code //verb}, web composer, skill
     * {@code activate:}/{@code deactivate:}): an optional leading
     * {@code //}, then a verb, then the raw remainder carried as a
     * single {@code text} argument. A richer {@code key=value} grammar
     * is an open decision (planning/engine-commands.md §5).
     *
     * @throws IllegalArgumentException when the line has no verb
     */
    public static EngineCommand parse(String line) {
        String s = line == null ? "" : line.trim();
        if (s.startsWith("//")) {
            s = s.substring(2).trim();
        }
        if (s.isEmpty()) {
            throw new IllegalArgumentException("empty command");
        }
        int sp = indexOfWhitespace(s);
        String verb = sp < 0 ? s : s.substring(0, sp);
        String rest = sp < 0 ? "" : s.substring(sp + 1).trim();
        Map<String, Object> args = rest.isEmpty() ? Map.of() : Map.of("text", rest);
        return new EngineCommand(verb, args);
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
