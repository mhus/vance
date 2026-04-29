package de.mhus.vance.foot.command;

import de.mhus.vance.api.thinkprocess.ProcessCompactRequest;
import de.mhus.vance.api.thinkprocess.ProcessCompactResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code /compact [processName]} — manually trigger memory compaction
 * for a think-process. With no argument, defaults to the active
 * process selected via {@code /process-activate}.
 *
 * <p>The Brain replies with how many messages were rolled into the
 * summary and the resulting memory id; on a no-op (history too short
 * or summarizer error) the reply explains why.
 */
@Component
public class ProcessCompactCommand implements SlashCommand {

    private final ConnectionService connection;
    private final SessionService sessions;
    private final ChatTerminal terminal;

    public ProcessCompactCommand(
            ConnectionService connection,
            SessionService sessions,
            ChatTerminal terminal) {
        this.connection = connection;
        this.sessions = sessions;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "compact";
    }

    @Override
    public String description() {
        return "Compact older chat history of a think-process into a memory summary. "
                + "Args: [processName] (defaults to active).";
    }

    @Override
    public List<ArgSpec> argSpec() {
        return List.of(ArgSpec.of("processName", ArgKind.PROCESS));
    }

    @Override
    public void execute(List<String> args) throws Exception {
        String processName = args.isEmpty() ? sessions.activeProcess() : args.get(0);
        if (processName == null || processName.isBlank()) {
            terminal.error("No process given and no active process — "
                    + "use /process-activate <name> first or /compact <name>.");
            return;
        }

        ProcessCompactResponse response = connection.request(
                MessageType.PROCESS_COMPACT,
                ProcessCompactRequest.builder().processName(processName).build(),
                ProcessCompactResponse.class,
                Duration.ofSeconds(60));

        if (response.isCompacted()) {
            terminal.info("→ compacted " + response.getProcessName()
                    + ": " + response.getMessagesCompacted() + " messages → "
                    + response.getSummaryChars() + " chars (memory="
                    + response.getMemoryId() + ")");
        } else {
            terminal.info("→ compact " + response.getProcessName()
                    + " skipped: " + response.getReason());
        }
    }
}
