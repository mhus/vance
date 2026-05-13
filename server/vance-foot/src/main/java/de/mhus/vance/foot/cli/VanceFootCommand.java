package de.mhus.vance.foot.cli;

import de.mhus.vance.foot.agent.ClientAgentDocService;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ide.IdeBridgeService;
import de.mhus.vance.foot.session.AutoBootstrapService;
import de.mhus.vance.foot.session.SessionResumeFlow;
import de.mhus.vance.foot.tools.ClientToolService;
import de.mhus.vance.foot.transfer.FootTransferService;
import de.mhus.vance.foot.ui.ChatRepl;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Picocli root for {@code vance-foot}. The CLI used to have {@code chat}
 * and {@code daemon} subcommands; both have been folded back here, driven
 * by orthogonal flags. This avoids the silent drift where adding an
 * option to one subcommand left the other behind.
 *
 * <h2>Mode flags</h2>
 *
 * <ul>
 *   <li>{@code --no-ui} — skip the JLine REPL; Spring stays alive until
 *       SIGTERM/SIGINT. Used for headless daemons.</li>
 *   <li>{@code --no-connect} — skip the auto-connect on startup; the
 *       {@code /connect} slash-command is the manual trigger.</li>
 *   <li>{@code --no-bootstrap} — skip the {@code vance.bootstrap}
 *       auto-bootstrap after the welcome frame.</li>
 *   <li>{@code --no-tools} — disable everything that exposes local
 *       resources to the brain: {@code ClientTool} registration, agent
 *       doc upload, file transfer, IDE bridge. Web-style restricted
 *       client.</li>
 *   <li>{@code --profile=<name>} — WebSocket profile sent on connect
 *       (default {@code "foot"}, see {@code Profiles}).</li>
 *   <li>{@code --name=<value>} — human-readable client identifier sent
 *       on connect; falls back to {@code vance.auth.username}.</li>
 *   <li>{@code --project=<name>} — override
 *       {@code vance.bootstrap.project-id}.</li>
 *   <li>{@code --agent-file=<path>} — override the agent doc cascade
 *       ({@code ./agent.md} → {@code ./CLAUDE.md}).</li>
 *   <li>{@code --intellij-claude},
 *       {@code --intellij-mcp[=<url>]},
 *       {@code --intellij-mcp-default} — IDE bridges (planning/foot-ide-bridge.md).</li>
 *   <li>{@code -d} — bundle for {@code --profile=daemon --no-ui
 *       --log-file=./vance-foot-daemon.log}.</li>
 *   <li>{@code -w} — bundle for {@code --profile=web --no-tools}.</li>
 * </ul>
 *
 * <h2>App-level shims (parsed in {@code VanceFootApplication.main})</h2>
 *
 * Stripped from {@code args} before Picocli sees them:
 * {@code --config <path>} / {@code -c}, {@code --log-file <path>},
 * {@code --rest-api}.
 */
@Component
@Command(
        name = "vance-foot",
        mixinStandardHelpOptions = true,
        version = "vance-foot 0.1.0",
        description = {
                "Spring-based CLI client for the Vance Brain.",
                "",
                "App-level flags (intercepted before Picocli):",
                "  --config <path>  / -c <path>   merge YAML on top of defaults",
                "                                 (multiple allowed; later wins)",
                "  --log-file <path>              write the application log here",
                "                                 (default: vance-foot.log)",
                "  --rest-api                     enable the debug REST server"
        })
public class VanceFootCommand implements Callable<Integer> {

    private static final String INTELLIJ_MCP_DEFAULT_URL = "http://127.0.0.1:64342/stream";
    private static final String DAEMON_DEFAULT_LOG_FILE = "./vance-foot-daemon.log";

    @Option(names = "--no-connect",
            description = "Do not open the WebSocket on startup; use /connect later.")
    boolean noConnect;

    @Option(names = "--no-bootstrap",
            description = "Skip the auto-bootstrap from vance.bootstrap config after welcome.")
    boolean noBootstrap;

    @Option(names = "--no-ui",
            description = "Skip the JLine REPL; the JVM stays alive until SIGINT/SIGTERM.")
    boolean noUi;

    @Option(names = "--no-tools",
            description = "Refuse local-resource integration (ClientTools, agent doc, "
                    + "file transfer, IDE bridge). Use for web-style restricted clients.")
    boolean noTools;

    @Option(names = "--profile",
            paramLabel = "<name>",
            description = "WebSocket profile sent on connect "
                    + "(foot, web, mobile, daemon, or a tenant-defined name). "
                    + "Default: foot.")
    @Nullable String profile;

    @Option(names = "--name",
            paramLabel = "<value>",
            description = "Client identifier sent on connect. "
                    + "Falls back to vance.auth.username when omitted.")
    @Nullable String name;

    @Option(names = "--agent-file",
            paramLabel = "<path>",
            description = "Override the agent doc uploaded to the brain. "
                    + "Without this option the cascade is ./agent.md → ./CLAUDE.md.")
    @Nullable Path agentFile;

    @Option(names = "--project",
            paramLabel = "<name>",
            description = "Override vance.bootstrap.project-id. Clears any "
                    + "configured session-id (start fresh).")
    @Nullable String project;

    @Option(names = "--intellij-claude",
            description = "Connect to a running Claude Code IDE plugin "
                    + "for editor context (at_mentioned, selection_changed, "
                    + "/ide commands).")
    boolean intellijClaude;

    @Option(names = "--intellij-mcp",
            paramLabel = "<url>",
            description = "Register an IntelliJ MCP-Server endpoint with the brain "
                    + "(streamable-HTTP). Tools become available after welcome.")
    @Nullable String intellijMcpUrl;

    @Option(names = "--intellij-mcp-default",
            description = "Same as --intellij-mcp=" + INTELLIJ_MCP_DEFAULT_URL
                    + " — the JetBrains plugin's stock endpoint.")
    boolean intellijMcpDefault;

    @Option(names = {"-d", "--daemon"},
            description = "Daemon mode: --profile=daemon --no-ui "
                    + "--log-file=" + DAEMON_DEFAULT_LOG_FILE + ". "
                    + "Mutually exclusive with -w.")
    boolean daemonShortcut;

    @Option(names = {"-w", "--web"},
            description = "Web-restricted mode: --profile=web --no-tools. "
                    + "Mutually exclusive with -d.")
    boolean webShortcut;

    @Option(names = "--resume",
            description = "Skip auto-bootstrap and show a session picker. "
                    + "Combine with --project to filter, --eddie for Eddie sessions, "
                    + "or --last to auto-pick the most recent.")
    boolean resume;

    @Option(names = "--last",
            description = "With --resume: auto-pick the most recent matching "
                    + "session instead of opening the picker. Implies --resume.")
    boolean last;

    @Option(names = "--eddie",
            description = "With --resume: filter to Eddie sessions instead of foot. "
                    + "Mutually exclusive with --project. Implies --resume.")
    boolean eddie;

    private final ChatRepl repl;
    private final ConnectionService connection;
    private final ChatTerminal terminal;
    private final FootConfig config;
    private final IdeBridgeService ideBridge;
    private final ClientAgentDocService agentDoc;
    private final ClientToolService clientTools;
    private final FootTransferService transfers;
    private final SessionResumeFlow resumeFlow;

    public VanceFootCommand(ChatRepl repl,
                            ConnectionService connection,
                            ChatTerminal terminal,
                            FootConfig config,
                            IdeBridgeService ideBridge,
                            ClientAgentDocService agentDoc,
                            ClientToolService clientTools,
                            FootTransferService transfers,
                            SessionResumeFlow resumeFlow) {
        this.repl = repl;
        this.connection = connection;
        this.terminal = terminal;
        this.config = config;
        this.ideBridge = ideBridge;
        this.agentDoc = agentDoc;
        this.clientTools = clientTools;
        this.transfers = transfers;
        this.resumeFlow = resumeFlow;
    }

    @Override
    public Integer call() throws Exception {
        if (daemonShortcut && webShortcut) {
            terminal.error("-d and -w are mutually exclusive (different profiles).");
            return 2;
        }
        applyDaemonShortcut();
        applyWebShortcut();

        // --resume validation. --last and --eddie imply --resume; --eddie
        // is mutually exclusive with --project (Eddie sessions live in
        // user-scoped projects we resolve from the profile, so an
        // explicit project would over-constrain things).
        if (last) resume = true;
        if (eddie) resume = true;
        if (eddie && project != null && !project.isBlank()) {
            terminal.error("--eddie and --project are mutually exclusive.");
            return 2;
        }
        if (resume) {
            // Skip the welcome-handler auto-bootstrap; SessionResumeFlow
            // will fire bootstrap manually after the picker resolves.
            System.setProperty(AutoBootstrapService.SKIP_PROPERTY, "true");
        }

        if (noBootstrap) {
            System.setProperty(AutoBootstrapService.SKIP_PROPERTY, "true");
        }
        if (project != null && !project.isBlank()) {
            config.getBootstrap().setProjectId(project);
            config.getBootstrap().setSessionId(null);
        }
        if (profile != null && !profile.isBlank()) {
            config.getClient().setProfile(profile);
        }
        if (name != null && !name.isBlank()) {
            config.getClient().setName(name);
        }
        if (noTools) {
            // Hard switch — every local-resource exposer respects this
            // flag at runtime instead of suppressing them per-bean. See
            // ClientToolService / ClientAgentDocService / FootTransferService
            // for the guards. The IDE-bridge flags below short-circuit
            // on noTools so the bridge is never started.
            clientTools.setSuppressed(true);
            agentDoc.setSuppressed(true);
            transfers.setSuppressed(true);
        }
        if (agentFile != null) {
            agentDoc.setOverridePath(agentFile);
        }
        if (intellijClaude && !noTools) {
            config.getIde().getClaude().setEnabled(true);
            ideBridge.start(Paths.get("").toAbsolutePath());
        }
        if (intellijMcpUrl != null && !intellijMcpUrl.isBlank()) {
            if (intellijMcpDefault) {
                terminal.error("Use either --intellij-mcp=<url> or --intellij-mcp-default, not both.");
                return 2;
            }
            if (!noTools) {
                config.getIde().getIntellijMcp().setUrl(intellijMcpUrl.trim());
            }
        } else if (intellijMcpDefault && !noTools) {
            config.getIde().getIntellijMcp().setUrl(INTELLIJ_MCP_DEFAULT_URL);
        }

        if (!noConnect) {
            try {
                connection.connect();
            } catch (Exception e) {
                terminal.error("Auto-connect failed: " + e.getMessage()
                        + (noUi ? "" : " — type /connect to retry."));
            }
        }
        if (resume) {
            SessionResumeFlow.Outcome outcome = resumeFlow.run(eddie, project, last);
            switch (outcome) {
                case CANCELLED -> { return 1; }
                case NO_MATCH, LIST_FAILED -> { return 2; }
                case BOOTSTRAPPED -> {
                    // bootstrap fired; continue to REPL
                }
            }
        }
        if (noUi) {
            terminal.info("vance-foot running headless — Ctrl-C to exit.");
            CountDownLatch park = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(park::countDown, "vance-foot-shutdown"));
            park.await();
        } else {
            repl.run();
        }
        return 0;
    }

    private void applyDaemonShortcut() {
        if (!daemonShortcut) return;
        if (profile == null || profile.isBlank()) {
            profile = "daemon";
        }
        noUi = true;
        // --log-file is parsed in VanceFootApplication.main and sets
        // logging.file.name as a system property; if the user did not
        // pass one we set the daemon default here.
        if (System.getProperty("logging.file.name") == null
                || System.getProperty("logging.file.name").isBlank()) {
            System.setProperty("logging.file.name", DAEMON_DEFAULT_LOG_FILE);
        }
    }

    private void applyWebShortcut() {
        if (!webShortcut) return;
        if (profile == null || profile.isBlank()) {
            profile = "web";
        }
        noTools = true;
    }
}
