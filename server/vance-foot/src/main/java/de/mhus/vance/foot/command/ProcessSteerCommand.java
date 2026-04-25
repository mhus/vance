package de.mhus.vance.foot.command;

import de.mhus.vance.api.thinkprocess.ProcessSteerRequest;
import de.mhus.vance.api.thinkprocess.ProcessSteerResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code /process-steer <processName> <message...>} — sends a user chat
 * message into a think-process running in the bound session. The Brain
 * replies synchronously with an ack carrying the new process state; the
 * actual assistant content streams in afterwards through
 * {@code chat-message-stream-chunk} and {@code chat-message-appended}
 * notifications, handled by the dedicated handlers.
 */
@Component
public class ProcessSteerCommand implements SlashCommand {

    private final ConnectionService connection;
    private final ChatTerminal terminal;

    public ProcessSteerCommand(ConnectionService connection, ChatTerminal terminal) {
        this.connection = connection;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "process-steer";
    }

    @Override
    public String description() {
        return "Send a chat message to a think-process. Args: <processName> <message...>.";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        if (args.size() < 2) {
            terminal.error("Usage: /process-steer <processName> <message...>");
            return;
        }
        String processName = args.get(0);
        String content = String.join(" ", args.subList(1, args.size()));

        ProcessSteerResponse response = connection.request(
                MessageType.PROCESS_STEER,
                ProcessSteerRequest.builder()
                        .processName(processName)
                        .content(content)
                        .build(),
                ProcessSteerResponse.class,
                Duration.ofSeconds(30));

        terminal.info("→ steered " + response.getProcessName()
                + " (status=" + response.getStatus() + ")");
    }
}
