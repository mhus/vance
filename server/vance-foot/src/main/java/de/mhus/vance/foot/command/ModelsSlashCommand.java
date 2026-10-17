package de.mhus.vance.foot.command;

import de.mhus.vance.api.chatmodel.ChatModelDto;
import de.mhus.vance.api.chatmodel.ChatModelListDto;
import de.mhus.vance.api.chatmodel.ChatModelStateDto;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code /models [filter]} — list the chat models selectable in the bound
 * session's project (the Web-UI picker's list), filtered by every given
 * term, with the active process's current model marked {@code *}.
 */
@Component
public class ModelsSlashCommand implements SlashCommand {

    private final ChatModelSupport support;
    private final ChatTerminal terminal;

    public ModelsSlashCommand(ChatModelSupport support, ChatTerminal terminal) {
        this.support = support;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "models";
    }

    @Override
    public String description() {
        return "List the selectable chat models (optional filter terms); * marks the current one.";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        String process = support.requireActiveProcess();
        String project = support.boundProject();
        if (process == null || project == null) {
            return;
        }
        ChatModelListDto list = support.listModels(project);
        String current = null;
        try {
            ChatModelStateDto state = support.run(process, "model", "get").state();
            current = state == null ? null : state.getModel();
        } catch (ChatModelSupport.ModelCommandException e) {
            terminal.warn("current model unknown: " + e.getMessage());
        }
        List<ChatModelDto> shown = ChatModelSupport.filter(list.getModels(), String.join(" ", args));
        if (shown.isEmpty()) {
            terminal.info(args.isEmpty() ? "No selectable models in this project." : "No model matches the filter.");
            return;
        }
        terminal.info(
                shown.size() + " of " + list.getModels().size() + " model(s) — price input / output per 1M tokens:");
        for (ChatModelDto m : shown) {
            terminal.info(ChatModelSupport.formatRow(m, m.getSpec().equals(current)));
        }
        terminal.info("Switch with /model <provider:model> or pick in /ui-models.");
    }
}
