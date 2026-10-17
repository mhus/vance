package de.mhus.vance.foot.command;

import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code /model [<spec>|get|clear]} — switch the active process's model,
 * or show it. Thin wrapper over the {@code //model} engine command: the
 * brain validates the spec (catalog, configured provider instance,
 * {@code chat.allowed-models}) and applies it from the next turn. The
 * reasoning level stays with {@code //thinking}; {@code /ui-models} sets both.
 */
@Component
public class ModelSlashCommand implements SlashCommand {

    private final ChatModelSupport support;
    private final ChatTerminal terminal;

    public ModelSlashCommand(ChatModelSupport support, ChatTerminal terminal) {
        this.support = support;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "model";
    }

    @Override
    public String description() {
        return "Show or switch the active process's model: /model [<provider:model>|get|clear].";
    }

    @Override
    public List<ArgSpec> argSpec() {
        return List.of(ArgSpec.enumOf("spec", List.of("get", "clear")));
    }

    @Override
    public void execute(List<String> args) throws Exception {
        String process = support.requireActiveProcess();
        if (process == null) {
            return;
        }
        String text = args.isEmpty() ? "get" : String.join(" ", args).trim();
        try {
            ChatModelSupport.Reply reply = support.run(process, "model", text);
            if (reply.message() != null) {
                terminal.info(reply.message());
            }
        } catch (ChatModelSupport.ModelCommandException e) {
            terminal.error("/model: " + e.getMessage());
        }
    }
}
