package de.mhus.vance.foot.command;

import de.mhus.vance.foot.markdown.MarkdownRenderState;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * {@code /markdown [on|off|toggle|status]} — flip the lite markdown
 * renderer for assistant chat replies. Without an argument it prints
 * the current state. {@code toggle} is convenient for keybindings; the
 * default startup value comes from {@code vance.ui.markdown.enabled}.
 */
@Component
public class MarkdownCommand implements SlashCommand {

    private final ChatTerminal terminal;
    private final MarkdownRenderState state;

    public MarkdownCommand(ChatTerminal terminal, MarkdownRenderState state) {
        this.terminal = terminal;
        this.state = state;
    }

    @Override
    public String name() {
        return "markdown";
    }

    @Override
    public String description() {
        return "Toggle the lite markdown renderer for assistant replies (on|off|toggle|status).";
    }

    @Override
    public List<ArgSpec> argSpec() {
        return List.of(ArgSpec.enumOf("mode", List.of("on", "off", "toggle", "status")));
    }

    @Override
    public void execute(List<String> args) {
        if (args.isEmpty()) {
            report();
            return;
        }
        String mode = args.get(0).toLowerCase(Locale.ROOT);
        switch (mode) {
            case "on" -> {
                state.setEnabled(true);
                terminal.info("markdown: on");
            }
            case "off" -> {
                state.setEnabled(false);
                terminal.info("markdown: off");
            }
            case "toggle" -> {
                state.setEnabled(!state.isEnabled());
                terminal.info("markdown: " + (state.isEnabled() ? "on" : "off"));
            }
            case "status" -> report();
            default -> terminal.error("Unknown mode: " + mode + " (use on|off|toggle|status)");
        }
    }

    private void report() {
        terminal.info("markdown: " + (state.isEnabled() ? "on" : "off"));
    }
}
