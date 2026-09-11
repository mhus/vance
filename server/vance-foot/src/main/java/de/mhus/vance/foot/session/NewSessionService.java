package de.mhus.vance.foot.session;

import de.mhus.vance.api.thinkprocess.BootstrappedProcess;
import de.mhus.vance.api.thinkprocess.SessionBootstrapRequest;
import de.mhus.vance.api.thinkprocess.SessionBootstrapResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Starts a fresh session in the current project — the terminal twin of the
 * Web-UI {@code +} button. Shared by {@code /new} (text path) and
 * {@code /ui-new} (recipe picker), so both end in the same bootstrap
 * round-trip and the same reporting.
 *
 * <p>The request mirrors the Web-UI modal exactly: {@code session-bootstrap}
 * with no extra processes and the picked recipe as {@code chatRecipe}
 * ({@code null} for the "Default" entry), so the brain's defaulting and the
 * project's {@code default}-recipe overrides apply the same way on both
 * surfaces. Binding replaces the current session — the previous one stays
 * alive server-side and is resumable later, same semantics as
 * {@code /session-bootstrap}.
 */
@Service
public class NewSessionService {

    private static final Duration BOOTSTRAP_TIMEOUT = Duration.ofSeconds(30);

    private final ConnectionService connection;
    private final SessionService sessions;
    private final ChatTerminal terminal;
    private final FootConfig config;

    public NewSessionService(
            ConnectionService connection, SessionService sessions, ChatTerminal terminal, FootConfig config) {
        this.connection = connection;
        this.sessions = sessions;
        this.terminal = terminal;
        this.config = config;
    }

    /**
     * Where a fresh session lands: the project of the bound session, or —
     * when nothing is bound yet — the configured bootstrap project. Both
     * empty means the caller cannot start a session and should say so
     * (bind first, or configure {@code vance.bootstrap.project-id}).
     */
    public @Nullable String resolveProject() {
        SessionService.BoundSession bound = sessions.current();
        if (bound != null && bound.projectId() != null && !bound.projectId().isBlank()) {
            return bound.projectId();
        }
        FootConfig.Bootstrap bootstrap = config.getBootstrap();
        if (bootstrap != null
                && bootstrap.getProjectId() != null
                && !bootstrap.getProjectId().isBlank()) {
            return bootstrap.getProjectId();
        }
        return null;
    }

    /**
     * Creates the session and binds it. {@code chatRecipe == null} is the
     * modal's "Default" entry — the brain resolves the project default.
     */
    public void bootstrapNew(String projectId, @Nullable String chatRecipe) throws Exception {
        SessionBootstrapResponse response = connection.request(
                MessageType.SESSION_BOOTSTRAP,
                SessionBootstrapRequest.builder()
                        .projectId(projectId)
                        .processes(List.of())
                        .chatRecipe(chatRecipe)
                        .takeover(false)
                        .build(),
                SessionBootstrapResponse.class,
                BOOTSTRAP_TIMEOUT);

        sessions.bind(response.getSessionId(), response.getProjectId());
        terminal.info((response.isSessionCreated() ? "Session created: " : "Session resumed: ")
                + response.getSessionId() + " (project=" + response.getProjectId() + ")"
                + (chatRecipe == null ? "" : ", recipe=" + chatRecipe));
        if (response.getChatProcessName() != null
                && !response.getChatProcessName().isBlank()) {
            terminal.info("  ° session chat: " + response.getChatProcessName() + " (engine=" + response.getChatEngine()
                    + ")");
            sessions.setActiveProcess(response.getChatProcessName());
            terminal.info("Active process: " + response.getChatProcessName());
        }
        for (BootstrappedProcess p : nullSafe(response.getProcessesCreated())) {
            terminal.info("  + process created: " + p.getName() + " (engine=" + p.getEngine() + ", status="
                    + p.getStatus() + ")");
        }
        for (BootstrappedProcess p : nullSafe(response.getProcessesSkipped())) {
            terminal.info("  ° process skipped (already exists): " + p.getName() + " (engine=" + p.getEngine() + ")");
        }
    }

    private static <T> List<T> nullSafe(@Nullable List<T> list) {
        return list == null ? List.of() : list;
    }
}
