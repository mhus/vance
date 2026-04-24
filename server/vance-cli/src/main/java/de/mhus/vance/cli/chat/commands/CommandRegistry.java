package de.mhus.vance.cli.chat.commands;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Dispatches slash-command input to registered {@link Command}s.
 *
 * <p>Parsing rules: input must start with {@code /}. The token after the slash
 * (lower-cased) is the command name; anything after the first whitespace is
 * split into whitespace-separated arguments and passed to the command. Quoting
 * is intentionally not supported yet — keep argument shapes simple.
 */
public class CommandRegistry {

    private final Map<String, Command> byName = new HashMap<>();
    private final List<Command> ordered = new ArrayList<>();

    /**
     * Registers a command. A later registration with the same name or alias
     * overwrites the earlier one, which is useful for tests but should not be
     * relied on in production code.
     */
    public void register(Command command) {
        byName.put(command.name().toLowerCase(Locale.ROOT), command);
        for (String alias : command.aliases()) {
            byName.put(alias.toLowerCase(Locale.ROOT), command);
        }
        if (!ordered.contains(command)) {
            ordered.add(command);
        }
    }

    /** All commands in registration order — used by {@code /help}. */
    public Collection<Command> all() {
        return Collections.unmodifiableList(ordered);
    }

    public @Nullable Command find(String name) {
        return byName.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Parses and dispatches a raw input line. {@code raw} is the line exactly
     * as submitted by the user (no trimming has happened yet). Non-slash input
     * is reported as an error through the context.
     */
    public void execute(String raw, CommandContext ctx) {
        String line = raw.trim();
        if (line.isEmpty()) {
            return;
        }
        if (!line.startsWith("/")) {
            ctx.error("Free-text messages are not wired up yet. Type /help for commands.");
            return;
        }
        String body = line.substring(1).trim();
        if (body.isEmpty()) {
            ctx.error("Empty command — type /help.");
            return;
        }
        String[] tokens = body.split("\\s+");
        String name = tokens[0];
        String[] args = new String[tokens.length - 1];
        System.arraycopy(tokens, 1, args, 0, args.length);

        Command cmd = find(name);
        if (cmd == null) {
            ctx.error("Unknown command: /" + name + " — type /help.");
            return;
        }
        cmd.execute(ctx, args);
    }
}
