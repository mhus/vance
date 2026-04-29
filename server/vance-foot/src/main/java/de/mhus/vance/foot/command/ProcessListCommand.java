package de.mhus.vance.foot.command;

import de.mhus.vance.api.thinkprocess.ProcessListRequest;
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
 * {@code /process-list [--all]} — lists the live think-processes in
 * the bound session. Terminated processes (STOPPED / DONE / STALE)
 * are hidden by default — pass {@code --all} to include them.
 */
@Component
public class ProcessListCommand implements SlashCommand {

    private final ConnectionService connection;
    private final ChatTerminal terminal;
    private final SuggestionCache suggestionCache;

    public ProcessListCommand(
            ConnectionService connection,
            ChatTerminal terminal,
            SuggestionCache suggestionCache) {
        this.connection = connection;
        this.terminal = terminal;
        this.suggestionCache = suggestionCache;
    }

    @Override
    public String name() {
        return "process-list";
    }

    @Override
    public String description() {
        return "List live think-processes in the current session "
                + "(--all also shows terminated ones).";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        boolean includeTerminated = false;
        for (String arg : args) {
            if ("--all".equals(arg) || "-a".equals(arg)) {
                includeTerminated = true;
            } else {
                terminal.error("Usage: /process-list [--all]");
                return;
            }
        }

        ProcessListResponse response = connection.request(
                MessageType.PROCESS_LIST,
                ProcessListRequest.builder()
                        .includeTerminated(includeTerminated)
                        .build(),
                ProcessListResponse.class,
                Duration.ofSeconds(10));
        // Refill the suggestion cache so a Tab right after /process-list
        // is instant. We always remember non-terminated names — that's
        // what the completer wants regardless of the --all flag.
        if (response.getProcesses() != null) {
            suggestionCache.rememberProcesses(response.getProcesses().stream()
                    .map(ProcessSummary::getName)
                    .filter(s -> s != null && !s.isBlank())
                    .toList());
        }
        if (response.getProcesses() == null || response.getProcesses().isEmpty()) {
            if (response.getHiddenTerminated() != null) {
                terminal.info("No live processes — "
                        + response.getHiddenTerminated()
                        + " terminated (use /process-list --all to see).");
            } else {
                terminal.info("No processes in this session.");
            }
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
        if (response.getHiddenTerminated() != null) {
            terminal.info("(" + response.getHiddenTerminated()
                    + " terminated hidden — /process-list --all to see)");
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
