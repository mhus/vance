package de.mhus.vance.foot.command;

import de.mhus.vance.api.thinkprocess.ProcessCreateRequest;
import de.mhus.vance.api.thinkprocess.ProcessCreateResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code /process-create <engine> [name]} — instantiate a think-process from
 * a registered engine in the bound session. If {@code name} is omitted, the
 * engine name is used as the process name. Sets the new process as the
 * active one so the next free-text line steers it directly.
 */
@Component
public class ProcessCreateCommand implements SlashCommand {

    private final ConnectionService connection;
    private final SessionService sessions;
    private final ChatTerminal terminal;

    public ProcessCreateCommand(ConnectionService connection,
                                SessionService sessions,
                                ChatTerminal terminal) {
        this.connection = connection;
        this.sessions = sessions;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "process-create";
    }

    @Override
    public String description() {
        return "Create a think-process from an engine. Args: <engine> [name].";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        if (args.isEmpty() || args.size() > 2) {
            terminal.error("Usage: /process-create <engine> [name]");
            return;
        }
        String engine = args.get(0);
        String processName = args.size() == 2 ? args.get(1) : engine;

        ProcessCreateResponse response = connection.request(
                MessageType.PROCESS_CREATE,
                ProcessCreateRequest.builder()
                        .engine(engine)
                        .name(processName)
                        .build(),
                ProcessCreateResponse.class,
                Duration.ofSeconds(15));

        sessions.setActiveProcess(response.getName());
        terminal.info("Process created: " + response.getName()
                + " (engine=" + response.getEngine()
                + ", status=" + response.getStatus()
                + ", id=" + response.getThinkProcessId() + ")");
        terminal.info("Active process: " + response.getName());
    }
}
