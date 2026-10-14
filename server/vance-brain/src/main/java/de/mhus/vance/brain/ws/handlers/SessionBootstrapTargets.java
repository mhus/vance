package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.session.SessionStatus;
import de.mhus.vance.api.thinkprocess.SessionBootstrapRequest;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Which project a {@code session-bootstrap} lands in — the one decision the
 * bootstrap handler and the Face-Pod's routing have to make identically.
 *
 * <p>The handler runs on the project's home pod; the Face-Pod has to send the
 * frame there before the handler ever sees it. Both therefore ask this class:
 * the handler to pick the session, the Face-Pod to pick the tunnel. When the
 * two disagreed, a bootstrap was handled on a pod that does not own the
 * project, and its claim was refused.
 *
 * <ul>
 *   <li>{@code sessionId} set — that session's project.</li>
 *   <li>{@code projectId} set — that project.</li>
 *   <li>neither — the newest resumable session of the caller
 *       ({@link #autoResumeCandidates}), else the caller's own hub
 *       ({@link #defaultProject}).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class SessionBootstrapTargets {

    private final SessionService sessionService;

    /**
     * The caller's sessions a project-less bootstrap may resume, newest
     * activity first: not closed, not bound to another connection, created
     * with the caller's connection profile, and not in the homeless tenant
     * project — sessions from before user hubs got a home pod may still sit
     * there, and nothing can be brought up for them.
     */
    public List<SessionDocument> autoResumeCandidates(ConnectionContext ctx) {
        return sessionService.listForUser(ctx.getTenantId(), ctx.getUserId()).stream()
                .filter(s -> s.getStatus() != SessionStatus.CLOSED)
                .filter(s -> !ProjectService.isHomeless(s.getProjectId()))
                .filter(s -> s.getBoundConnectionId() == null)
                .filter(s -> profileMatches(ctx, s))
                .sorted(Comparator.comparing(
                        SessionDocument::getLastActivityAt, Comparator.nullsLast(Comparator.<Instant>reverseOrder())))
                .toList();
    }

    /**
     * Where a bootstrap without project and without a resumable session
     * creates its session: the caller's own hub {@code _user_<login>}.
     */
    public String defaultProject(ConnectionContext ctx) {
        return HomeBootstrapService.hubProjectName(ctx.getUserId());
    }

    /**
     * The project the bootstrap will run in, for routing. Empty when an
     * explicit {@code sessionId} does not resolve — the handler answers that
     * with its own 404.
     */
    public Optional<String> projectOf(ConnectionContext ctx, SessionBootstrapRequest request) {
        if (!isBlank(request.getSessionId())) {
            return sessionService.findBySessionId(request.getSessionId()).map(SessionDocument::getProjectId);
        }
        if (!isBlank(request.getProjectId())) {
            return Optional.of(request.getProjectId());
        }
        return Optional.of(autoResumeCandidates(ctx).stream()
                .findFirst()
                .map(SessionDocument::getProjectId)
                .orElseGet(() -> defaultProject(ctx)));
    }

    /** Whether {@code session} was created with the connection's profile. */
    public static boolean profileMatches(ConnectionContext ctx, SessionDocument session) {
        String sessionProfile = session.getProfile();
        String ctxProfile = ctx.getProfile();
        return sessionProfile != null && sessionProfile.equals(ctxProfile);
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
