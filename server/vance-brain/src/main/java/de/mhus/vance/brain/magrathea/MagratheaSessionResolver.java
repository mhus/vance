package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.ws.Profiles;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Resolves the system session a Magrathea workflow run uses for its
 * {@code agent_task} spawns. One session per workflow run — display
 * name {@code _magrathea_<runId>}; reused for every {@code agent_task} of
 * the same run so the chat history stays correlated.
 *
 * <p>Pattern mirrors {@code SchedulerSystemSessionResolver}: idempotent
 * lookup by {@code (project, displayName, system=true)}, lazy create
 * with a {@link SessionService#markBootstrapped} flip so the spawned
 * process sees a runnable session.
 */
@Service
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class MagratheaSessionResolver {

    private static final String DISPLAY_NAME_PREFIX = "_magrathea_";

    private final SessionService sessionService;

    /**
     * Get or create the {@code _magrathea_<runId>} session for this run.
     * {@code startedBy} ends up as the session's {@code userId} so
     * inbox-routing and notifications go to the right user; falls back
     * to {@code "@system"} when null.
     */
    public SessionDocument resolve(
            String tenantId, String projectId, String workflowRunId, String startedBy) {
        String displayName = displayNameFor(workflowRunId);
        return sessionService.findSystemSession(tenantId, projectId, displayName)
                .orElseGet(() -> createFresh(tenantId, projectId,
                        startedBy == null ? "@system" : startedBy, displayName));
    }

    private SessionDocument createFresh(
            String tenantId, String projectId, String runAs, String displayName) {
        SessionDocument created = sessionService.create(
                tenantId,
                runAs,
                projectId,
                displayName,
                Profiles.SCHEDULER,
                /*clientVersion*/ "magrathea",
                /*clientName*/ null,
                /*system*/ true);
        sessionService.markBootstrapped(created.getSessionId());
        log.info("Magrathea system-session created project='{}' name='{}' sessionId='{}' runAs='{}'",
                projectId, displayName, created.getSessionId(), runAs);
        return created;
    }

    public static String displayNameFor(String workflowRunId) {
        return DISPLAY_NAME_PREFIX + workflowRunId;
    }
}
