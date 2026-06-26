package de.mhus.vance.foot.command;

import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * {@code /aa} — toggle auto-AI mode (alias of "auto AI").
 *
 * <ul>
 *   <li>{@code /aa} — flip the flag.</li>
 *   <li>{@code /aa on} / {@code /aa off} — explicit set.</li>
 *   <li>{@code /aa status} — print the current state, no change.</li>
 * </ul>
 *
 * <p>When on, every chat line gets {@code @ai } prepended so the
 * agent reacts to every turn from this foot instance — typical
 * "pilot" role in a multi-user session. Start a line with
 * {@code @no } to skip the agent for one turn (the prefix is
 * stripped before send).
 *
 * <p>Mirrors the {@code /aa} command in the Web-UI composer. See
 * {@code planning/multi-user-sessions.md} §6.
 */
@Component
public class AutoAiSlashCommand implements SlashCommand {

    private final AutoAiService autoAi;
    private final ChatTerminal terminal;

    public AutoAiSlashCommand(AutoAiService autoAi, ChatTerminal terminal) {
        this.autoAi = autoAi;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "aa";
    }

    @Override
    public String description() {
        return "Toggle auto-AI mode — prepend @ai to every chat line. Use @no to skip for one turn.";
    }

    @Override
    public List<ArgSpec> argSpec() {
        return List.of(ArgSpec.enumOf("mode", List.of("on", "off", "status")));
    }

    @Override
    public void execute(List<String> args) {
        if (args.isEmpty()) {
            autoAi.toggle();
        } else {
            String mode = args.get(0).toLowerCase(Locale.ROOT);
            switch (mode) {
                case "on" -> autoAi.set(true);
                case "off" -> autoAi.set(false);
                case "status" -> { /* read-only */ }
                default -> {
                    terminal.error("Usage: /aa [on|off|status].");
                    return;
                }
            }
        }
        if (autoAi.isOn()) {
            terminal.info("Auto-AI: ON — every message goes to the AI (escape with @no).");
        } else {
            terminal.info("Auto-AI: OFF — type @ai to address the agent.");
        }
    }
}
