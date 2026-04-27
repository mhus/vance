package de.mhus.vance.foot.session;

import de.mhus.vance.api.thinkprocess.BootstrappedProcess;
import de.mhus.vance.api.thinkprocess.ProcessSpec;
import de.mhus.vance.api.thinkprocess.SessionBootstrapRequest;
import de.mhus.vance.api.thinkprocess.SessionBootstrapResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.BrainException;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Sends a {@code session-bootstrap} automatically after the Brain's welcome
 * frame, using the {@link FootConfig.Bootstrap} block from the loaded config.
 * Mirrors {@code vance-cli}'s install-on-welcome behaviour.
 *
 * <h2>Threading</h2>
 * The trigger arrives on the WebSocket-listener thread (via
 * {@code WelcomeHandler}). Calling {@code connection.request} from there
 * would deadlock — the reply must travel through the same listener thread we
 * are blocking. So we hand off to a dedicated single-thread executor.
 *
 * <h2>Skip conditions</h2>
 * <ul>
 *   <li>Config has no processes — nothing to do.</li>
 *   <li>Neither {@code projectId} nor {@code sessionId} set — invalid combo.</li>
 *   <li>System property {@code vance.bootstrap.skip=true} — explicit opt-out
 *       (set by {@code --no-bootstrap}).</li>
 * </ul>
 *
 * <p>Each connect fires at most once. If the user manually
 * {@code /disconnect}s and reconnects, a new welcome triggers a new bootstrap.
 */
@Service
public class AutoBootstrapService {

    public static final String SKIP_PROPERTY = "vance.bootstrap.skip";

    private final FootConfig config;
    private final ConnectionService connection;
    private final SessionService sessions;
    private final ChatTerminal terminal;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vance-foot-bootstrap");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    /**
     * @param connection {@code @Lazy} to break the cycle:
     *   ConnectionService → MessageDispatcher → WelcomeHandler →
     *   AutoBootstrapService → ConnectionService.
     */
    public AutoBootstrapService(FootConfig config,
                                @Lazy ConnectionService connection,
                                SessionService sessions,
                                ChatTerminal terminal) {
        this.config = config;
        this.connection = connection;
        this.sessions = sessions;
        this.terminal = terminal;
    }

    /**
     * Called by {@code WelcomeHandler}. Returns immediately; the actual
     * bootstrap runs on this service's executor.
     */
    public void triggerAfterWelcome() {
        if (Boolean.parseBoolean(System.getProperty(SKIP_PROPERTY, "false"))) {
            terminal.verbose("Auto-bootstrap skipped (--no-bootstrap).");
            return;
        }
        FootConfig.Bootstrap b = config.getBootstrap();
        if (b == null) {
            terminal.verbose("Auto-bootstrap skipped (no vance.bootstrap in config).");
            return;
        }
        if (b.getProjectId() == null && b.getSessionId() == null) {
            terminal.verbose(
                    "Auto-bootstrap skipped (no projectId or sessionId in vance.bootstrap).");
            return;
        }
        // Empty `processes` is fine — the brain auto-spawns the session-chat
        // (Arthur) on its own. Explicit entries here add worker processes
        // alongside the chat orchestrator.
        if (!inFlight.compareAndSet(false, true)) {
            terminal.verbose("Auto-bootstrap already in flight — ignoring duplicate trigger.");
            return;
        }
        executor.submit(() -> {
            try {
                runBootstrap(b);
            } finally {
                inFlight.set(false);
            }
        });
    }

    private void runBootstrap(FootConfig.Bootstrap b) {
        List<FootConfig.BootstrapProcess> configured = b.getProcesses() == null
                ? List.of() : b.getProcesses();
        List<ProcessSpec> specs = new ArrayList<>(configured.size());
        for (FootConfig.BootstrapProcess p : configured) {
            String engine = p.getEngine() == null || p.getEngine().isBlank()
                    ? null : p.getEngine();
            String recipe = p.getRecipe() == null || p.getRecipe().isBlank()
                    ? null : p.getRecipe();
            String defaultName = recipe != null ? recipe
                    : (engine != null ? engine : "process");
            specs.add(ProcessSpec.builder()
                    .engine(engine)
                    .recipe(recipe)
                    .name(p.getName() == null || p.getName().isBlank()
                            ? defaultName : p.getName())
                    .title(p.getTitle())
                    .goal(p.getGoal())
                    .params(p.getParams() == null || p.getParams().isEmpty()
                            ? null : p.getParams())
                    .build());
        }
        SessionBootstrapRequest req = SessionBootstrapRequest.builder()
                .projectId(b.getProjectId())
                .sessionId(b.getSessionId())
                .processes(specs)
                .initialMessage(b.getInitialMessage())
                .build();

        terminal.verbose("Auto-bootstrap firing — "
                + (b.getSessionId() != null ? "resume " + b.getSessionId() : "create in " + b.getProjectId())
                + ", " + specs.size() + " process(es)"
                + (b.getInitialMessage() == null ? "" : ", with initial message"));

        SessionBootstrapResponse response;
        try {
            response = connection.request(
                    MessageType.SESSION_BOOTSTRAP, req,
                    SessionBootstrapResponse.class,
                    Duration.ofSeconds(30));
        } catch (BrainException e) {
            terminal.error("Auto-bootstrap failed: " + e.getMessage());
            return;
        } catch (Exception e) {
            terminal.error("Auto-bootstrap failed: " + e.getMessage());
            return;
        }

        sessions.bind(response.getSessionId(), response.getProjectId());
        terminal.info((response.isSessionCreated() ? "Bootstrap → session created: " : "Bootstrap → session resumed: ")
                + response.getSessionId() + " (project=" + response.getProjectId() + ")");
        if (response.getChatProcessName() != null) {
            terminal.info("  ° session chat: " + response.getChatProcessName()
                    + " (engine=" + response.getChatEngine() + ")");
        }
        for (BootstrappedProcess p : response.getProcessesCreated()) {
            terminal.info("  + process created: " + p.getName()
                    + " (engine=" + p.getEngine() + ", status=" + p.getStatus() + ")");
        }
        for (BootstrappedProcess p : response.getProcessesSkipped()) {
            terminal.info("  ° process skipped (already exists): " + p.getName()
                    + " (engine=" + p.getEngine() + ")");
        }

        // Active-process priority:
        //   1. explicit initialMessage target — the user is mid-conversation with it
        //   2. session-chat orchestrator (Arthur) — typing chats with the orchestrator
        //   3. first explicit worker the user listed
        //   4. first skipped (resume case) — keeps idempotent re-bootstrap stable
        @Nullable String active = response.getSteeredProcessName();
        if (active == null && response.getChatProcessName() != null) {
            active = response.getChatProcessName();
        }
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

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
