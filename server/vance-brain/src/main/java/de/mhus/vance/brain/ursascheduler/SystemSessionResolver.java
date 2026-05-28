package de.mhus.vance.brain.ursascheduler;

import de.mhus.vance.api.ws.Profiles;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Looks up (or lazily creates) the dedicated {@code _scheduler_<name>}
 * system session for a scheduler — see
 * {@code specification/scheduler.md} §6.
 *
 * <p>The session is reused across runs and across brain restarts: the
 * deterministic {@code (project, displayName, system=true)} key lets a
 * fresh brain reconnect to the same session row a previous brain
 * created. Lifecycle: created in {@code INIT}, transitioned to
 * {@code IDLE} by {@link SessionService#markBootstrapped} so the
 * subsequent process-spawn sees a runnable session.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SystemSessionResolver {

    private final SessionService sessionService;

    /**
     * Returns the system session for {@code schedulerName}, creating it
     * on first use. {@code runAs} ends up as the session's {@code userId}
     * — Inbox-routing and downstream notifications go to that user.
     */
    public SessionDocument resolve(
            String tenantId, String projectId, String schedulerName, String runAs) {
        String displayName = UrsaSchedulerSourceKeys.systemSessionDisplayName(schedulerName);
        return sessionService.findSystemSession(tenantId, projectId, displayName)
                .orElseGet(() -> createFresh(tenantId, projectId, runAs, displayName));
    }

    private SessionDocument createFresh(
            String tenantId, String projectId, String runAs, String displayName) {
        SessionDocument created = sessionService.create(
                tenantId,
                runAs,
                projectId,
                displayName,
                Profiles.SCHEDULER,
                /*clientVersion*/ "scheduler",
                /*clientName*/ null,
                /*system*/ true);
        // Skip INIT — system sessions never go through the regular
        // client-bind bootstrap that flips them to IDLE.
        sessionService.markBootstrapped(created.getSessionId());
        log.info("Scheduler system-session created project='{}' name='{}' sessionId='{}' runAs='{}'",
                projectId, displayName, created.getSessionId(), runAs);
        return created;
    }
}
