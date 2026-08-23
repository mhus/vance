package de.mhus.vance.foot.cli;

import de.mhus.vance.foot.agent.ClientAgentDocService;
import de.mhus.vance.foot.auth.ProjectBindingApplier;
import de.mhus.vance.foot.auth.ProjectBindingStore;
import de.mhus.vance.foot.auth.SessionAnchor;
import de.mhus.vance.foot.auth.SessionAnchorStore;
import de.mhus.vance.foot.auth.VancePaths;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.config.VanceProjectConfig;
import de.mhus.vance.foot.config.VanceProjectConfigApplier;
import de.mhus.vance.foot.config.VanceProjectConfigStore;
import de.mhus.vance.foot.command.SkillCommandHelper;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ide.IdeBridgeService;
import de.mhus.vance.foot.markdown.MarkdownRenderState;
import de.mhus.vance.foot.permission.PermissionService;
import de.mhus.vance.foot.session.AutoBootstrapService;
import de.mhus.vance.foot.session.LocalSessionPickerView;
import de.mhus.vance.foot.session.SessionResumeFlow;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.tools.ClientToolService;
import de.mhus.vance.foot.tools.pack.FootToolPackRegistry;
import de.mhus.vance.foot.tools.pack.ProjectPackConsent;
import de.mhus.vance.foot.transfer.FootTransferService;
import de.mhus.vance.foot.ui.ChatRepl;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.ColorResolver;
import de.mhus.vance.foot.ui.Verbosity;
import de.mhus.vance.foot.ui.WindowTitleService;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import lombok.extern.slf4j.Slf4j;
import org.jline.utils.AttributedStyle;
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
 *   <li>{@code --no-login} — skip the auto-connect with stored credentials
 *       on startup so a fresh {@code /login} can be run (re-authenticate as
 *       a different user / tenant without deleting {@code access.yaml}).</li>
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
 * {@code --config <path>}, {@code --log-file <path>}, {@code --rest-api}.
 * ({@code --config} has no {@code -c} short form — {@code -c} is
 * {@code --continue} below.)
 */
@Component
@Slf4j
@Command(
        name = "vance-foot",
        mixinStandardHelpOptions = true,
        versionProvider = FootVersionProvider.class,
        description = {
                "Spring-based CLI client for the Vance Brain.",
                "",
                "App-level flags (intercepted before Picocli):",
                "  --config <path>                merge YAML on top of defaults",
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

    @Option(names = "--no-login",
            description = "Skip the auto-connect with stored credentials on startup so "
                    + "a fresh /login can be run. Maps to --no-connect for the connect "
                    + "step, but signals the intent to re-authenticate rather than just "
                    + "connect later.")
    boolean noLogin;

    @Option(names = "--no-bootstrap",
            description = "Skip the auto-bootstrap from vance.bootstrap config after welcome.")
    boolean noBootstrap;

    @Option(names = "--no-local",
            description = "Ignore a project-local ./.vancetope directory; use only the "
                    + "global home ($VANCE_HOME or ~/.vancetope) for config and credentials.")
    boolean noLocal;

    @Option(names = "--no-ui",
            description = "Skip the JLine REPL; the JVM stays alive until SIGINT/SIGTERM.")
    boolean noUi;

    @Option(names = "--no-tools",
            description = "Refuse local-resource integration (ClientTools, agent doc, "
                    + "file transfer, IDE bridge). Use for web-style restricted clients.")
    boolean noTools;

    @Option(names = "--no-sandbox",
            description = "Disable the file/exec permission sandbox: all brain-issued "
                    + "file and exec commands run unrestricted. Overrides the "
                    + "permissions.yaml sandbox switch for this run.")
    boolean noSandbox;

    @Option(names = "--no-tool-output",
            description = "Suppress the cosmetic 'tool used' block in the chat output. "
                    + "Equivalent to vance.ui.tool-output.enabled=false.")
    boolean noToolOutput;

    @Option(names = "--no-markdown",
            description = "Skip the lite markdown renderer for assistant replies; "
                    + "print raw text instead. Useful for copy-paste and debugging. "
                    + "Toggle at runtime with /markdown on|off.")
    boolean noMarkdown;

    @Option(names = "--audit",
            description = "Enable conversation audit logging: append every chat "
                    + "message to .vancetope/conversations/<YYYY>-<MM>/<sessionId>.jsonl. "
                    + "Overrides .vancetope/config.yaml and application.yaml defaults.")
    boolean audit;

    @Option(names = "--no-audit",
            description = "Disable conversation audit logging for this run. "
                    + "Overrides .vancetope/config.yaml and application.yaml defaults.")
    boolean noAudit;

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

    @Option(names = "--session",
            paramLabel = "<id>",
            description = "Resume this exact session by id (skips the "
                    + "--resume picker). Sets vance.bootstrap.session-id; "
                    + "the brain decides whether the project is implied "
                    + "from the session.")
    @Nullable String sessionId;

    @Option(names = "--recipe",
            paramLabel = "<name>",
            description = "Use this recipe as the session-chat orchestrator "
                    + "on bootstrap. Sets vance.bootstrap.chat-recipe — "
                    + "applies on session create, ignored on resume.")
    @Nullable String recipe;

    @Option(names = "--skill",
            paramLabel = "<name>",
            description = "One-shot: after connecting and bootstrapping a session, "
                    + "activate <name> on the chat process (firing its action: turn), "
                    + "print the result and exit — no interactive REPL. Requires a "
                    + "session (--project / --session / -c, or a bootstrapped directory).")
    @Nullable String skill;

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

    @Option(names = {"-c", "--continue"},
            description = "Resume a session from this directory's local history "
                    + "(stored in .vancetope/session.yaml). With --session=<id> resume "
                    + "that exact session; with --name=<n> resume the newest local entry "
                    + "with that name. With neither, resume the single local entry, or "
                    + "open a picker of the local sessions when there are several "
                    + "(with All Sessions / New Session / Cancel escapes). If the local "
                    + "history is empty, fall back to the most recent matching session "
                    + "on the server. Mutually exclusive with --resume / --last / --eddie.")
    boolean continueSession;

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
    private final WindowTitleService windowTitle;
    private final MarkdownRenderState markdownState;
    private final PermissionService permissions;
    private final VancePaths vancePaths;
    private final ProjectBindingStore bindingStore;
    private final ProjectBindingApplier bindingApplier;
    private final SessionAnchorStore sessionAnchorStore;
    private final VanceProjectConfigStore projectConfigStore;
    private final VanceProjectConfigApplier projectConfigApplier;
    private final ColorResolver colorResolver;
    private final SkillCommandHelper skillHelper;
    private final SessionService sessions;
    private final OneShotTurnGate oneShotGate;
    private final FootToolPackRegistry toolPacks;
    private final ProjectPackConsent packConsent;

    public VanceFootCommand(ChatRepl repl,
                            ConnectionService connection,
                            ChatTerminal terminal,
                            FootConfig config,
                            IdeBridgeService ideBridge,
                            ClientAgentDocService agentDoc,
                            ClientToolService clientTools,
                            FootTransferService transfers,
                            SessionResumeFlow resumeFlow,
                            WindowTitleService windowTitle,
                            MarkdownRenderState markdownState,
                            PermissionService permissions,
                            VancePaths vancePaths,
                            ProjectBindingStore bindingStore,
                            ProjectBindingApplier bindingApplier,
                            SessionAnchorStore sessionAnchorStore,
                            VanceProjectConfigStore projectConfigStore,
                            VanceProjectConfigApplier projectConfigApplier,
                            ColorResolver colorResolver,
                            SkillCommandHelper skillHelper,
                            SessionService sessions,
                            OneShotTurnGate oneShotGate,
                            FootToolPackRegistry toolPacks,
                            ProjectPackConsent packConsent) {
        this.repl = repl;
        this.connection = connection;
        this.terminal = terminal;
        this.config = config;
        this.ideBridge = ideBridge;
        this.agentDoc = agentDoc;
        this.clientTools = clientTools;
        this.transfers = transfers;
        this.resumeFlow = resumeFlow;
        this.windowTitle = windowTitle;
        this.markdownState = markdownState;
        this.permissions = permissions;
        this.vancePaths = vancePaths;
        this.bindingStore = bindingStore;
        this.bindingApplier = bindingApplier;
        this.sessionAnchorStore = sessionAnchorStore;
        this.projectConfigStore = projectConfigStore;
        this.projectConfigApplier = projectConfigApplier;
        this.colorResolver = colorResolver;
        this.skillHelper = skillHelper;
        this.sessions = sessions;
        this.oneShotGate = oneShotGate;
        this.toolPacks = toolPacks;
        this.packConsent = packConsent;
    }

    @Override
    public Integer call() throws Exception {
        // Once at startup: version to the log file (for support) and to the
        // console (WARN-only file/console split means an INFO log would never
        // reach the user's terminal, so print it explicitly).
        String versionLine = FootVersionProvider.format(config.getBuild());
        log.info(versionLine);
        terminal.info(versionLine);

        if (daemonShortcut && webShortcut) {
            terminal.error("-d and -w are mutually exclusive (different profiles).");
            return 2;
        }
        applyDaemonShortcut();
        applyWebShortcut();

        // Overlay a project-local (or global-home) .vancetope/project.eddie.yaml binding
        // onto the config BEFORE the CLI-flag overrides below, so precedence is
        // application.yaml < project.eddie.yaml < flags. A stored binding that sets a
        // project also arms the welcome-time auto-bootstrap, so a directory with
        // a saved login boots straight into its project.
        applyLocalBinding();
        Optional<VanceProjectConfig> projectConfig = applyProjectConfig();

        // Headless runs (daemon / --no-ui) have no user to answer a sandbox
        // prompt — set this before connect() so an early tool-invoke
        // auto-denies instead of blocking on input that never comes.
        if (noUi) {
            permissions.setInteractive(false);
        }

        // --no-login skips the auto-connect with stored credentials. Every
        // resume / continue / explicit-session path needs a live connection,
        // so combining --no-login with any of them is a contradiction —
        // reject early with a clear message instead of letting the resume
        // flow fail later with a cryptic "no token" error.
        if (noLogin && (resume || last || eddie || continueSession
                || (sessionId != null && !sessionId.isBlank()))) {
            terminal.error("--no-login is mutually exclusive with "
                    + "--resume / --last / --eddie / --session / --continue "
                    + "(all require an auto-connect).");
            return 2;
        }

        // Both startup session pickers — the -c local-history picker below and
        // the --resume server picker further down — are Lanterna fullscreen
        // excursions, and InterfaceService.runFullscreen needs a registered
        // JLine terminal. The REPL only builds one in repl.run(), which is at
        // the very bottom of this method, so without this the pickers could
        // never open. Idempotent: repl.run() reuses this terminal.
        // (--last skips the picker, --no-ui has no user to show it to.)
        if (!noUi && (continueSession || ((resume || eddie) && !last))) {
            try {
                repl.ensureTerminal();
            } catch (Exception e) {
                // No usable TTY. The pickers degrade on their own (newest
                // local entry / "--last" hint) and repl.run() will report the
                // real failure if it still can't build one.
                terminal.println(Verbosity.VERBOSE,
                        "Could not open a terminal for the session picker: %s", e.getMessage());
            }
        }

        // -c/--continue: resume a session from this directory's local history
        // (written to .vancetope/session.yaml on every bootstrap). Resolve it
        // into the existing selectors:
        //   - with an explicit --session, resume that exact session;
        //   - with --name, resume the newest local entry whose name matches;
        //   - with neither, resume the single local entry, or open a picker of
        //     the local sessions when there are several;
        //   - an empty local history falls back to --resume --last (newest
        //     matching session from the server).
        if (continueSession) {
            if (resume || last || eddie) {
                terminal.error("--continue is mutually exclusive with "
                        + "--resume / --last / --eddie.");
                return 2;
            }
            if (sessionId != null && !sessionId.isBlank()) {
                // Explicit --session wins — resume that exact session.
                terminal.info("Continuing session " + sessionId + " (explicit --session).");
            } else {
                List<SessionAnchor.SessionEntry> localEntries =
                        sessionAnchorStore.loadEntries(vancePaths.activeDir());
                if (name != null && !name.isBlank()) {
                    // --name filters the local history.
                    SessionAnchor.SessionEntry byName = localEntries.stream()
                            .filter(e -> name.equals(e.getName()))
                            .findFirst()
                            .orElse(null);
                    if (byName != null) {
                        sessionId = byName.getSessionId();
                        terminal.info("Continuing last session " + sessionId
                                + " (" + sessionAnchorStore.file(vancePaths.activeDir()) + ").");
                    } else {
                        resume = true;
                        last = true;
                        terminal.info("No local session named '" + name
                                + "' — resuming the most recent from the server.");
                    }
                } else if (localEntries.isEmpty()) {
                    resume = true;
                    last = true;
                    terminal.info("No stored session for this directory — "
                            + "resuming the most recent from the server.");
                } else if (localEntries.size() == 1) {
                    sessionId = localEntries.get(0).getSessionId();
                    terminal.info("Continuing last session " + sessionId
                            + " (" + sessionAnchorStore.file(vancePaths.activeDir()) + ").");
                } else {
                    // Several local sessions and no selector — show a picker
                    // limited to the local history (plus escape actions).
                    LocalSessionPickerView.Result pick =
                            resumeFlow.continueFromLocal(localEntries);
                    if (pick == null) {
                        // Nothing picked / picker unavailable — resume newest.
                        sessionId = localEntries.get(0).getSessionId();
                        terminal.info("Continuing last session " + sessionId
                                + " (" + sessionAnchorStore.file(vancePaths.activeDir()) + ").");
                    } else {
                        switch (pick.choice()) {
                            case RESUME_ENTRY -> {
                                sessionId = pick.entry().getSessionId();
                                terminal.info("Continuing session " + sessionId
                                        + " (" + sessionAnchorStore.file(vancePaths.activeDir()) + ").");
                            }
                            case ALL_SESSIONS -> {
                                // Full server picker at the connect stage.
                                resume = true;
                                terminal.info("Opening full session picker.");
                            }
                            case NEW_SESSION -> {
                                // Leave the local sessionId null AND drop a
                                // configured vance.bootstrap.session-id — a
                                // pinned session in application.yaml /
                                // .vancetope/config.yaml would otherwise
                                // survive the choice and resume anyway.
                                config.getBootstrap().setSessionId(null);
                                terminal.info("Starting a new session.");
                            }
                            case CANCEL -> {
                                terminal.info("Continue cancelled.");
                                return 1;
                            }
                        }
                    }
                }
            }
        }

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
        // --session is the explicit resume-by-id form. It bypasses the
        // picker entirely, so combining it with --resume / --last / --eddie
        // is a contradiction.
        if (sessionId != null && !sessionId.isBlank() && (resume || last || eddie)) {
            terminal.error("--session is mutually exclusive with --resume / --last / --eddie.");
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
        // --session wins over --project's session-clear: order matters
        // because --project intentionally drops a stale configured
        // sessionId to "start fresh"; the explicit --session form
        // re-arms it so the user can pin both project and session in one
        // command (project for filtering, session for the exact resume).
        if (sessionId != null && !sessionId.isBlank()) {
            config.getBootstrap().setSessionId(sessionId.trim());
        }
        if (recipe != null && !recipe.isBlank()) {
            config.getBootstrap().setChatRecipe(recipe.trim());
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
            // Clear any IDE defaults that config.yaml may have set —
            // --no-tools is a hard override.
            config.getIde().getClaude().setEnabled(false);
            config.getIde().getIntellijMcp().setUrl(null);
        }
        // Tool packs load here, not from a bean-init hook: the layer set
        // (--no-local), the toolPacks: selection from .vancetope/config.yaml
        // and whether a terminal exists to confirm a project-defined pack
        // are all only settled at this point. --no-tools skips the load
        // entirely — suppressing the registration downstream would still
        // have spawned every MCP subprocess.
        if (noTools) {
            terminal.println(Verbosity.VERBOSE, "Skipping tool packs (--no-tools).");
        } else {
            packConsent.setInteractiveExpected(!noUi && (skill == null || skill.isBlank()));
            toolPacks.startBootLoad();
        }

        if (noSandbox) {
            permissions.disableSandbox();
        } else if (config.getIde().isNoSandboxDefault()) {
            // defaults.sandbox=false from .vancetope/config.yaml — CLI --no-sandbox
            // already handled above; this is the config.yaml path.
            permissions.disableSandbox();
        }
        if (noToolOutput) {
            config.getUi().getToolOutput().setEnabled(false);
        }
        if (noMarkdown) {
            config.getUi().getMarkdown().setEnabled(false);
            markdownState.setEnabled(false);
        }
        if (audit && noAudit) {
            terminal.error("--audit and --no-audit are mutually exclusive.");
            return 2;
        }
        if (audit) {
            config.getConversationCapture().setEnabled(true);
        }
        if (noAudit) {
            config.getConversationCapture().setEnabled(false);
        }
        if (agentFile != null) {
            agentDoc.setOverridePath(agentFile);
        }
        if (intellijClaude && !noTools) {
            config.getIde().getClaude().setEnabled(true);
            ideBridge.start(Paths.get("").toAbsolutePath());
        } else if (projectConfig.isPresent()
                && projectConfig.get().getDefaults() != null
                && projectConfig.get().getDefaults().isIntellijClaude()
                && !noTools) {
            // defaults.intellijClaude from .vancetope/config.yaml — CLI flag
            // already handled above; this is the config.yaml path.
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
        // defaults.intellijMcpDefault from config.yaml was already applied to
        // config.getIde().getIntellijMcp().setUrl() by the applier — no
        // additional side-effect needed here.

        // --no-login: the user wants to re-authenticate, so we must not
        // auto-connect with the stored access.yaml credentials. Maps to
        // --no-connect for the connect step, plus a hint pointing at /login
        // (not /connect) since the intent is a fresh login, not a reconnect
        // with the same stored token.
        if (noLogin) {
            noConnect = true;
            terminal.info("Skipping auto-login — run /login to authenticate.");
        }
        if (!noConnect) {
            windowTitle.setConnection("connecting…");
            try {
                connection.connect();
            } catch (Exception e) {
                windowTitle.setConnection("offline");
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
        if (!permissions.isSandboxEnabled()) {
            AttributedStyle sandboxStyle = colorResolver.sandboxWarn();
            if (sandboxStyle == null) sandboxStyle = AttributedStyle.DEFAULT;
            terminal.printBoxed(
                    Verbosity.WARN,
                    sandboxStyle,
                    List.of("⚠  SANDBOX DISABLED — all file & exec commands run unrestricted."));
        }

        if (skill != null && !skill.isBlank()) {
            return runSkillOneShot(skill.trim());
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

    /** Overall wait budget for the one-shot skill's action turn to settle. */
    private static final Duration ONE_SHOT_TURN_TIMEOUT = Duration.ofSeconds(180);
    /** How long to wait for the async bootstrap to publish an active process. */
    private static final Duration ONE_SHOT_BOOTSTRAP_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Non-interactive {@code --skill} mode: wait for the async bootstrap
     * to bind an active chat process, activate the skill (which fires its
     * {@code action:} turn on the brain), block until that turn settles,
     * then exit. The assistant reply renders to stdout through the normal
     * push-frame path ({@code ChatTerminal} falls back to plain stdout when
     * no REPL/LiveRegion is attached). Returns the process exit code.
     */
    private Integer runSkillOneShot(String skillName) throws Exception {
        if (noConnect || !connection.isOpen()) {
            terminal.error("--skill one-shot needs a live connection — cannot run offline.");
            return 2;
        }
        String process = awaitActiveProcess(ONE_SHOT_BOOTSTRAP_TIMEOUT);
        if (process == null) {
            terminal.error("--skill one-shot: no active session within "
                    + ONE_SHOT_BOOTSTRAP_TIMEOUT.toSeconds() + "s. Pass --project / --session / -c "
                    + "or run in a bootstrapped directory (and not with --no-bootstrap / -d).");
            return 2;
        }
        // Arm before activating so the turn's engine_turn_start/end edges
        // can never slip past the gate (state-based, not edge-based).
        oneShotGate.arm();
        terminal.info("One-shot: activating skill '" + skillName + "' on process '" + process + "' …");
        try {
            skillHelper.activate(process, skillName, /*oneShot*/ true, List.of());
        } catch (Exception e) {
            terminal.error("Skill activation failed: " + e.getMessage());
            return 1;
        }
        boolean settled = oneShotGate.awaitTurn(ONE_SHOT_TURN_TIMEOUT);
        if (!settled) {
            terminal.error("--skill one-shot: skill turn did not complete within "
                    + ONE_SHOT_TURN_TIMEOUT.toSeconds() + "s.");
            return 1;
        }
        // Small grace so the final chat-append render (processed on the WS
        // listener thread, ordered just before engine_turn_end) is flushed
        // before the context tears down.
        Thread.sleep(250);
        return 0;
    }

    /**
     * Polls {@link SessionService#activeProcess()} until the async
     * bootstrap publishes it, or the timeout elapses. Returns the process
     * name, or {@code null} on timeout.
     */
    private @Nullable String awaitActiveProcess(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        String p = sessions.activeProcess();
        while ((p == null || p.isBlank()) && System.nanoTime() < deadline) {
            Thread.sleep(100);
            p = sessions.activeProcess();
        }
        return (p == null || p.isBlank()) ? null : p;
    }

    private void applyLocalBinding() {
        vancePaths.setLocalEnabled(!noLocal);
        if (noLocal) {
            terminal.println(Verbosity.VERBOSE,
                    "Project-local .vance disabled (--no-local); using %s.",
                    vancePaths.globalHomeDir());
        }
        bindingStore.load(vancePaths.activeDir()).ifPresent(binding -> {
            bindingApplier.apply(binding, config);
            terminal.println(Verbosity.VERBOSE,
                    "Applied .vancetope/project.eddie.yaml from %s.", vancePaths.activeDir());
        });
    }

    private void applyDaemonShortcut() {
        if (!daemonShortcut) return;
        if (profile == null || profile.isBlank()) {
            profile = "daemon";
        }
        noUi = true;
        // Daemons don't bind a session — the brain rejects DAEMON_REGISTER
        // outside profile=daemon, and there's nothing for AutoBootstrap to
        // do here. Setting the skip-property unconditionally for -d avoids
        // a spurious "bootstrap failed" log on every daemon launch.
        noBootstrap = true;
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

    /**
     * Overlays {@code .vancetope/config.yaml} onto the running config,
     * after the {@code project.eddie.yaml} binding and before CLI flags. This
     * is the per-project config for non-credential settings (conversation
     * audit, default flags, future recipe presets, …). Absent file = no-op.
     *
     * @return the loaded project config, or empty if no file was present.
     *         The caller uses this to wire side-effects (ideBridge.start,
     *         permissions.disableSandbox) that can't be done inside the
     *         applier because they depend on other CLI flags (noTools, etc.).
     */
    private Optional<VanceProjectConfig> applyProjectConfig() {
        Optional<VanceProjectConfig> loaded = projectConfigStore.load(vancePaths.activeDir());
        loaded.ifPresent(projectConfig -> {
            projectConfigApplier.apply(projectConfig, config);
            terminal.println(Verbosity.VERBOSE,
                    "Applied .vancetope/config.yaml from %s.", vancePaths.activeDir());
        });
        return loaded;
    }

}
