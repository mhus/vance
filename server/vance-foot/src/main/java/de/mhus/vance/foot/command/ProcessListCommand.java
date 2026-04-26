package de.mhus.vance.foot.command;

import de.mhus.vance.api.thinkprocess.ProcessListResponse;
import de.mhus.vance.api.thinkprocess.ProcessSummary;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * {@code /process-list} — lists every think-process in the bound
 * session with status and engine.
 */
@Component
public class ProcessListCommand implements SlashCommand {

    private final ConnectionService connection;
    private final ChatTerminal terminal;

    public ProcessListCommand(ConnectionService connection, ChatTerminal terminal) {
        this.connection = connection;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "process-list";
    }

    @Override
    public String description() {
        return "List every think-process in the current session.";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        if (!args.isEmpty()) {
            terminal.error("Usage: /process-list");
            return;
        }
        ProcessListResponse response = connection.request(
                MessageType.PROCESS_LIST,
                null,
                ProcessListResponse.class,
                Duration.ofSeconds(10));
        if (response.getProcesses() == null || response.getProcesses().isEmpty()) {
            terminal.info("No processes in this session.");
            return;
        }
        terminal.info(String.format("%-20s %-12s %-20s %s",
                "NAME", "STATUS", "ENGINE", "GOAL"));
        for (ProcessSummary p : response.getProcesses()) {
            terminal.info(String.format("%-20s %-12s %-20s %s",
                    truncate(Objects.toString(p.getName(), ""), 20),
                    truncate(Objects.toString(p.getStatus(), ""), 12),
                    truncate(p.getThinkEngine() + (p.getThinkEngineVersion() == null
                            ? "" : "@" + p.getThinkEngineVersion()), 20),
                    truncate(Objects.toString(p.getGoal(), ""), 60)));
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
