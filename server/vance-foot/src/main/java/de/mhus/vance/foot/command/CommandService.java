package de.mhus.vance.foot.command;

import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Dispatcher for slash commands. Built from all {@link SlashCommand} beans
 * found in the application context. Every command is registered under its
 * {@link SlashCommand#name()} plus each of its {@link SlashCommand#aliases()};
 * all these names must be globally unique — a collision fails the boot.
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
            register(registry, command.name(), command);
            for (String alias : command.aliases()) {
                register(registry, alias, command);
            }
        }
        this.commands = Map.copyOf(registry);
    }

    private static void register(Map<String, SlashCommand> registry, String name, SlashCommand command) {
        SlashCommand previous = registry.put(name, command);
        if (previous != null && previous != command) {
            throw new IllegalStateException(
                    "Duplicate SlashCommand name/alias '" + name + "': "
                            + previous.getClass().getName() + " and "
                            + command.getClass().getName());
        }
    }

    /**
     * Distinct commands sorted by canonical name — useful for {@code /help}.
     * Aliases share their command instance, so each command appears once.
     */
    public Collection<SlashCommand> all() {
        Map<String, SlashCommand> byName = new TreeMap<>();
        for (SlashCommand command : commands.values()) {
            byName.putIfAbsent(command.name(), command);
        }
        return byName.values();
    }

    /** Every invocable name (canonical names + aliases), sorted — for tab-completion. */
    public SortedSet<String> names() {
        return new TreeSet<>(commands.keySet());
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
