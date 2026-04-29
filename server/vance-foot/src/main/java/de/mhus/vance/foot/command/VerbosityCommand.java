package de.mhus.vance.foot.command;

import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.Verbosity;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * {@code /verbosity [level]} — gets or sets the {@link ChatTerminal} threshold.
 * Without arguments, prints the current level and the available choices.
 */
@Component
public class VerbosityCommand implements SlashCommand {

    private final ChatTerminal terminal;

    public VerbosityCommand(ChatTerminal terminal) {
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "verbosity";
    }

    @Override
    public String description() {
        return "Get or set the output verbosity (ERROR..TRACE).";
    }

    @Override
    public List<ArgSpec> argSpec() {
        return List.of(ArgSpec.enumOf("level",
                Arrays.stream(Verbosity.values()).map(Enum::name).toList()));
    }

    @Override
    public void execute(List<String> args) {
        if (args.isEmpty()) {
            terminal.info("Current verbosity: " + terminal.threshold()
                    + " — choices: "
                    + Arrays.stream(Verbosity.values())
                            .map(Enum::name)
                            .collect(Collectors.joining(", ")));
            return;
        }
        Verbosity next;
        try {
            next = Verbosity.valueOf(args.get(0).toUpperCase());
        } catch (IllegalArgumentException e) {
            terminal.error("Unknown verbosity: " + args.get(0));
            return;
        }
        terminal.setThreshold(next);
        terminal.info("Verbosity set to " + next);
    }
}
