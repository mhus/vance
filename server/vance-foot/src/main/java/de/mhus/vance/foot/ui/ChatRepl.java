package de.mhus.vance.foot.ui;

import de.mhus.vance.api.thinkprocess.ProcessSteerRequest;
import de.mhus.vance.api.thinkprocess.ProcessSteerResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.command.CommandService;
import de.mhus.vance.foot.connection.BrainException;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.SessionService;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The JLine 3 REPL — default UI surface. Reads lines, routes leading-slash
 * input to {@link CommandService}, treats everything else as chat input.
 *
 * <p>Chat forwarding to the Brain is not yet implemented — for now chat lines
 * are echoed back through {@link ChatTerminal} so the loop is observable. The
 * real path goes through a {@code ChatService} that we add when the first
 * end-to-end Brain roundtrip is wired up.
 */
@Component
public class ChatRepl {

    private final CommandService commandService;
    private final ChatTerminal chatTerminal;
    private final InterfaceService interfaceService;
    private final SessionService sessions;
    private final ConnectionService connection;

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private @Nullable Terminal terminal;
    private @Nullable LineReader reader;

    public ChatRepl(CommandService commandService,
                    ChatTerminal chatTerminal,
                    InterfaceService interfaceService,
                    SessionService sessions,
                    ConnectionService connection) {
        this.commandService = commandService;
        this.chatTerminal = chatTerminal;
        this.interfaceService = interfaceService;
        this.sessions = sessions;
        this.connection = connection;
    }

    /** Allows {@code /quit} (or shutdown hooks) to exit the loop cleanly. */
    public void requestStop() {
        stopRequested.set(true);
    }

    public boolean isStopRequested() {
        return stopRequested.get();
    }

    /**
     * Runs the REPL until {@link #requestStop()} is called or the user signals
     * EOF (Ctrl-D). Sets up the JLine terminal once and tears it down at exit.
     */
    public void run() throws IOException {
        Terminal t = TerminalBuilder.builder()
                .system(true)
                .dumb(true)
                .build();
        this.terminal = t;
        chatTerminal.attach(t);
        interfaceService.registerJlineTerminal(t);

        LineReader r = LineReaderBuilder.builder()
                .terminal(t)
                .history(new DefaultHistory())
                .appName("vance-foot")
                .build();
        this.reader = r;
        chatTerminal.attachReader(r);

        chatTerminal.info("Vance Foot — type /help for commands, Ctrl-D to exit.");

        while (!stopRequested.get()) {
            String line;
            try {
                line = r.readLine(prompt());
            } catch (UserInterruptException ctrlC) {
                continue;
            } catch (EndOfFileException ctrlD) {
                break;
            }
            if (line == null || line.isBlank()) {
                continue;
            }
            if (line.startsWith("/")) {
                commandService.execute(line);
            } else {
                handleChat(line);
            }
        }
    }

    private String prompt() {
        SessionService.BoundSession bound = sessions.current();
        if (bound == null) {
            return "vance> ";
        }
        return "vance(" + bound.sessionId() + ")> ";
    }

    private void handleChat(String line) {
        if (sessions.current() == null) {
            chatTerminal.error("No bound session — /connect, then /session-resume or /session-create.");
            return;
        }
        String process = sessions.activeProcess();
        if (process == null) {
            chatTerminal.error("No active process — /process <name> first, "
                    + "or use /process-steer <name> <message>.");
            return;
        }
        try {
            ProcessSteerResponse response = connection.request(
                    MessageType.PROCESS_STEER,
                    ProcessSteerRequest.builder()
                            .processName(process)
                            .content(line)
                            .build(),
                    ProcessSteerResponse.class,
                    Duration.ofSeconds(30));
            chatTerminal.verbose("→ steered " + response.getProcessName()
                    + " (status=" + response.getStatus() + ")");
        } catch (BrainException e) {
            chatTerminal.error(e.getMessage());
        } catch (Exception e) {
            chatTerminal.error("Steer failed: " + e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        chatTerminal.attachReader(null);
        chatTerminal.attach(null);
        interfaceService.clearJlineTerminal();
        Terminal t = terminal;
        if (t != null) {
            try {
                t.close();
            } catch (IOException ignored) {
                // best-effort cleanup on shutdown
            }
        }
    }
}
