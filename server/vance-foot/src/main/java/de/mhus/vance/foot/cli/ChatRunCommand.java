package de.mhus.vance.foot.cli;

import de.mhus.vance.foot.agent.ClientAgentDocService;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ide.IdeBridgeService;
import de.mhus.vance.foot.session.AutoBootstrapService;
import de.mhus.vance.foot.ui.ChatRepl;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code vance-foot chat} — enters the JLine REPL. The WebSocket is opened
 * automatically before the prompt appears; pass {@code --no-connect} to skip
 * that and trigger the connection later via {@code /connect}.
 */
@Component
@Command(
        name = "chat",
        mixinStandardHelpOptions = true,
        description = "Open the chat REPL.")
public class ChatRunCommand implements Callable<Integer> {

    @Option(names = "--no-connect",
            description = "Do not open the WebSocket on startup; use /connect later.")
    boolean noConnect;

    @Option(names = "--no-bootstrap",
            description = "Skip the auto-bootstrap from vance.bootstrap config after welcome.")
    boolean noBootstrap;

    @Option(names = "--intellij-claude",
            description = "Connect to a running Claude Code IDE plugin "
                    + "(JetBrains/VSCode) for editor context like at_mentioned, "
                    + "selection_changed and /ide commands.")
    boolean intellijClaude;

    @Option(names = "--agent-file",
            paramLabel = "<path>",
            description = "Override the agent doc uploaded to the brain. "
                    + "Without this option the cascade is ./agent.md → ./CLAUDE.md.")
    @Nullable Path agentFile;

    @Option(names = "--project",
            paramLabel = "<name>",
            description = "Override vance.bootstrap.project-id from the config — "
                    + "starts the auto-bootstrap in this project. Clears any "
                    + "configured session-id (start fresh, never resume).")
    @Nullable String project;

    private final ChatRepl repl;
    private final ConnectionService connection;
    private final ChatTerminal terminal;
    private final FootConfig config;
    private final IdeBridgeService ideBridge;
    private final ClientAgentDocService agentDoc;

    public ChatRunCommand(ChatRepl repl,
                          ConnectionService connection,
                          ChatTerminal terminal,
                          FootConfig config,
                          IdeBridgeService ideBridge,
                          ClientAgentDocService agentDoc) {
        this.repl = repl;
        this.connection = connection;
        this.terminal = terminal;
        this.config = config;
        this.ideBridge = ideBridge;
        this.agentDoc = agentDoc;
    }

    @Override
    public Integer call() throws Exception {
        if (noBootstrap) {
            System.setProperty(AutoBootstrapService.SKIP_PROPERTY, "true");
        }
        if (project != null && !project.isBlank()) {
            config.getBootstrap().setProjectId(project);
            config.getBootstrap().setSessionId(null);
        }
        if (agentFile != null) {
            agentDoc.setOverridePath(agentFile);
        }
        if (intellijClaude) {
            config.getIde().getClaude().setEnabled(true);
            ideBridge.start(Paths.get("").toAbsolutePath());
        }
        if (!noConnect) {
            try {
                connection.connect();
            } catch (Exception e) {
                terminal.error("Auto-connect failed: " + e.getMessage()
                        + " — type /connect to retry.");
            }
        }
        repl.run();
        return 0;
    }
}
