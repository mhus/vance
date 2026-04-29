package de.mhus.vance.foot.command;

import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Dispatcher for slash commands. Built from all {@link SlashCommand} beans
 * found in the application context. Names must be unique — collisions fail
 * the boot.
 *
 * <p>The REPL passes the raw line including the leading slash to
 * {@link #execute(String)}; the service strips the slash, splits on whitespace,
 * and dispatches.
 */
@Service
public class CommandService {

    private final Map<String, SlashCommand> commands;
    private final ChatTerminal terminal;

    public CommandService(List<SlashCommand> commandBeans, ChatTerminal terminal) {
        this.terminal = terminal;
        Map<String, SlashCommand> registry = new HashMap<>();
        for (SlashCommand command : commandBeans) {
            String name = command.name();
            SlashCommand previous = registry.put(name, command);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate SlashCommand for name '" + name + "': "
                                + previous.getClass().getName() + " and "
                                + command.getClass().getName());
            }
        }
        this.commands = Map.copyOf(registry);
    }

    /** Returns commands sorted by name — useful for {@code /help}. */
    public Collection<SlashCommand> all() {
        return new TreeMap<>(commands).values();
    }

    /** Lookup by canonical name (no leading slash, lower-case). */
    public @Nullable SlashCommand find(String name) {
        if (name == null || name.isEmpty()) return null;
        return commands.get(name.toLowerCase());
    }

    /**
     * Parses and runs a slash command line. Returns {@code true} if a command
     * was found and executed (regardless of its outcome), {@code false} if no
     * match was found — the REPL can decide to surface that as an error.
     */
    public boolean execute(String rawLine) {
        String trimmed = rawLine.trim();
        if (!trimmed.startsWith("/")) {
            return false;
        }
        String[] tokens = trimmed.substring(1).trim().split("\\s+");
        if (tokens.length == 0 || tokens[0].isEmpty()) {
            return false;
        }
        String name = tokens[0].toLowerCase();
        SlashCommand command = commands.get(name);
        if (command == null) {
            terminal.error("Unknown command: /" + name + " — type /help for a list.");
            return false;
        }
        List<String> args = Arrays.asList(tokens).subList(1, tokens.length);
        try {
            command.execute(args);
        } catch (Exception e) {
            terminal.error("/" + name + " failed: " + e.getMessage());
        }
        return true;
    }
}
