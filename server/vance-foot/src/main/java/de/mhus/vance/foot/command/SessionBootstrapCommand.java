package de.mhus.vance.foot.command;

import de.mhus.vance.api.thinkprocess.BootstrappedProcess;
import de.mhus.vance.api.thinkprocess.ProcessSpec;
import de.mhus.vance.api.thinkprocess.SessionBootstrapRequest;
import de.mhus.vance.api.thinkprocess.SessionBootstrapResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code /session-bootstrap} — atomic session-or-process setup in one round-trip.
 * Combines {@code session-create}/{@code session-resume} with one or more
 * {@code process-create}s and an optional initial chat message.
 *
 * <h2>Syntax</h2>
 * <pre>
 *   /session-bootstrap &lt;projectId&gt; &lt;engine&gt;[:name] [more...] [-- &lt;initial message&gt;]
 *   /session-bootstrap @&lt;sessionId&gt; &lt;engine&gt;[:name] [more...] [-- &lt;initial message&gt;]
 * </pre>
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code /session-bootstrap instant-hole zaphod} —
 *       new session in instant-hole with one zaphod process</li>
 *   <li>{@code /session-bootstrap instant-hole zaphod marvin -- Hallo Bots} —
 *       new session, two processes, initial message steered to the first</li>
 *   <li>{@code /session-bootstrap @sess_xyz zaphod:bot1} —
 *       resume session, ensure a bot1 (engine zaphod) exists</li>
 * </ul>
 *
 * <p>The active process is set automatically — to the steered process if an
 * initial message was sent, otherwise to the first created process.
 */
@Component
public class SessionBootstrapCommand implements SlashCommand {

    private final ConnectionService connection;
    private final SessionService sessions;
    private final ChatTerminal terminal;

    public SessionBootstrapCommand(ConnectionService connection,
                                   SessionService sessions,
                                   ChatTerminal terminal) {
        this.connection = connection;
        this.sessions = sessions;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "session-bootstrap";
    }

    @Override
    public String description() {
        return "Create-or-resume session + ensure processes + optional first message.";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        if (args.isEmpty()) {
            printUsage();
            return;
        }
        int sepIdx = args.indexOf("--");
        List<String> head = sepIdx == -1 ? args : args.subList(0, sepIdx);
        @Nullable String initialMessage = sepIdx == -1
                ? null
                : String.join(" ", args.subList(sepIdx + 1, args.size())).trim();
        if (initialMessage != null && initialMessage.isEmpty()) {
            initialMessage = null;
        }

        if (head.size() < 2) {
            printUsage();
            return;
        }

        String target = head.get(0);
        @Nullable String projectId;
        @Nullable String sessionId;
        if (target.startsWith("@")) {
            sessionId = target.substring(1);
            projectId = null;
            if (sessionId.isEmpty()) {
                terminal.error("Empty session id after '@'.");
                return;
            }
        } else {
            projectId = target;
            sessionId = null;
        }

        List<ProcessSpec> specs = new ArrayList<>();
        for (String token : head.subList(1, head.size())) {
            int colon = token.indexOf(':');
            String engine = colon < 0 ? token : token.substring(0, colon);
            String processName = colon < 0 ? token : token.substring(colon + 1);
            if (engine.isEmpty() || processName.isEmpty()) {
                terminal.error("Invalid process spec: '" + token + "' — expected <engine> or <engine>:<name>.");
                return;
            }
            specs.add(ProcessSpec.builder().engine(engine).name(processName).build());
        }

        SessionBootstrapResponse response = connection.request(
                MessageType.SESSION_BOOTSTRAP,
                SessionBootstrapRequest.builder()
                        .projectId(projectId)
                        .sessionId(sessionId)
                        .processes(specs)
                        .initialMessage(initialMessage)
                        .build(),
                SessionBootstrapResponse.class,
                Duration.ofSeconds(30));

        sessions.bind(response.getSessionId(), response.getProjectId());
        terminal.info((response.isSessionCreated() ? "Session created: " : "Session resumed: ")
                + response.getSessionId() + " (project=" + response.getProjectId() + ")");
        for (BootstrappedProcess p : response.getProcessesCreated()) {
            terminal.info("  + process created: " + p.getName()
                    + " (engine=" + p.getEngine() + ", status=" + p.getStatus() + ")");
        }
        for (BootstrappedProcess p : response.getProcessesSkipped()) {
            terminal.info("  ° process skipped (already exists): " + p.getName()
                    + " (engine=" + p.getEngine() + ")");
        }

        @Nullable String active = response.getSteeredProcessName();
        if (active == null && !response.getProcessesCreated().isEmpty()) {
            active = response.getProcessesCreated().get(0).getName();
        } else if (active == null && !response.getProcessesSkipped().isEmpty()) {
            active = response.getProcessesSkipped().get(0).getName();
        }
        if (active != null) {
            sessions.setActiveProcess(active);
            terminal.info("Active process: " + active);
        }
        if (response.getSteeredProcessName() != null) {
            terminal.info("→ initial message steered to " + response.getSteeredProcessName());
        }
    }

    private void printUsage() {
        terminal.error("Usage: /session-bootstrap <projectId>|@<sessionId> <engine>[:name] [more...] [-- <initial message>]");
    }
}
