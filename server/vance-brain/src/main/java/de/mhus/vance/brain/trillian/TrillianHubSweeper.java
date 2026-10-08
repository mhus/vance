package de.mhus.vance.brain.trillian;

import de.mhus.vance.api.session.SessionStatus;
import de.mhus.vance.brain.session.SessionLifecycleService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.maintenance.ProjectDataHandler;
import de.mhus.vance.shared.project.maintenance.UserHubSweeper;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The brain's {@link UserHubSweeper} — and it sweeps <b>only Trillian hubs</b>
 * ({@value #TRILLIAN_HUB_PREFIX}…).
 *
 * <p><b>A named exception to "the brain never runs the project handlers".</b>
 * The maintenance sweep lives in the admin shell because its gates — a typed
 * confirmation, a pod drain — only exist at a terminal. A Trillian's end has a
 * different gate that is just as deliberate: the human closing or deleting the
 * control session (D1, "lifetime = control session"). That decision happens in
 * the brain, and without a sweep here the hub's rows — loop session, chat,
 * schedules, goals, settings — would outlive the account and be inherited by
 * the next Trillian minted under the same name. The exception is kept narrow
 * by shape, not by a flag: anything that is not a Trillian hub is refused, so
 * this class cannot reach a human's hub, let alone {@code _vance}.
 *
 * <p>Live sessions in the hub are closed through the session lifecycle first,
 * so the loop's engine stops on its lane before its rows go. Then every
 * handler runs, in the same order as in the shell; the project document is
 * removed only when all of them succeeded (it is the index back to the data,
 * and a re-run finishes a partial sweep).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrillianHubSweeper implements UserHubSweeper {

    /** {@code _user_} + {@code _trillian-} — the only hubs this class touches. */
    public static final String TRILLIAN_HUB_PREFIX = HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + "_trillian-";

    /** Lazy: handlers and the lifecycle service both sit on long bean chains. */
    private final ObjectProvider<ProjectDataHandler> handlers;

    private final ObjectProvider<SessionLifecycleService> lifecycleProvider;
    private final SessionService sessionService;
    private final ProjectService projectService;

    @Override
    public boolean sweepUserHub(String tenantId, String hubProjectName) {
        if (!hubProjectName.startsWith(TRILLIAN_HUB_PREFIX)) {
            throw new IllegalArgumentException(
                    "Refusing to sweep '" + hubProjectName + "' — the brain sweeps Trillian hubs only");
        }
        if (!projectService.existsByTenantAndName(tenantId, hubProjectName)) {
            return true;
        }
        closeLiveSessions(tenantId, hubProjectName);
        boolean allSucceeded = true;
        List<ProjectDataHandler> ordered = handlers.orderedStream()
                .sorted(Comparator.comparingInt(ProjectDataHandler::order).thenComparing(ProjectDataHandler::id))
                .toList();
        for (ProjectDataHandler handler : ordered) {
            try {
                handler.delete(tenantId, hubProjectName);
            } catch (RuntimeException e) {
                allSucceeded = false;
                log.warn(
                        "Trillian hub sweep '{}': handler '{}' failed: {}", hubProjectName, handler.id(), e.toString());
            }
        }
        if (!allSucceeded) {
            log.warn("Trillian hub sweep '{}' incomplete — project document kept for a re-run", hubProjectName);
            return false;
        }
        projectService.deleteUserHub(tenantId, hubProjectName);
        log.info("Trillian: swept hub '{}' ({} handlers)", hubProjectName, ordered.size());
        return true;
    }

    private void closeLiveSessions(String tenantId, String hubProjectName) {
        for (SessionDocument session : sessionService.listForProject(tenantId, hubProjectName)) {
            if (session.getStatus() == SessionStatus.CLOSED) {
                continue;
            }
            try {
                lifecycleProvider.getObject().closeWithCascade(session.getSessionId());
            } catch (RuntimeException e) {
                log.warn(
                        "Trillian hub sweep '{}': closing session '{}' failed: {}",
                        hubProjectName,
                        session.getSessionId(),
                        e.toString());
            }
        }
    }
}
