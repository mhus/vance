package de.mhus.vance.foot.command;

import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code /process <name>} — set the active think-process for free-text chat
 * input. {@code /process} alone clears the selection. With an active process,
 * lines without a leading slash are routed through {@code process-steer}
 * automatically.
 */
@Component
public class ProcessActivateCommand implements SlashCommand {

    private final SessionService sessions;
    private final ChatTerminal terminal;

    public ProcessActivateCommand(SessionService sessions, ChatTerminal terminal) {
        this.sessions = sessions;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "process";
    }

    @Override
    public String description() {
        return "Show or set the active process for free-text chat. Args: [<name>].";
    }

    @Override
    public void execute(List<String> args) {
        if (args.isEmpty()) {
            String active = sessions.activeProcess();
            terminal.info("Active process: " + (active == null ? "<none>" : active));
            return;
        }
        if (args.size() != 1) {
            terminal.error("Usage: /process [<name>]   ('/process' with no arg shows current)");
            return;
        }
        String name = args.get(0);
        if ("-".equals(name) || "none".equalsIgnoreCase(name)) {
            sessions.setActiveProcess(null);
            terminal.info("Active process cleared.");
            return;
        }
        sessions.setActiveProcess(name);
        terminal.info("Active process: " + name);
    }
}
