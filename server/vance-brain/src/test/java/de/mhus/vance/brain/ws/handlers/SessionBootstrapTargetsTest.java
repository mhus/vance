package de.mhus.vance.brain.ws.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.session.SessionStatus;
import de.mhus.vance.api.thinkprocess.SessionBootstrapRequest;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The routing decision the Face-Pod and the bootstrap handler share. Both ask
 * this class, so what it answers is where the handler runs — a candidate the
 * Face-Pod routes by and the handler then cannot resume is a bootstrap on the
 * wrong pod.
 */
class SessionBootstrapTargetsTest {

    private static final String TENANT = "acme";
    private static final String USER = "wile.coyote";
    private static final String PROFILE = "foot";

    private SessionService sessionService;
    private SessionBootstrapTargets targets;
    private ConnectionContext ctx;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        targets = new SessionBootstrapTargets(sessionService);
        ctx = new ConnectionContext(TENANT, USER, "Wile", PROFILE, "1.0", "cli", "ed-1", "10.0.0.1");
    }

    @Test
    void autoResumeCandidates_newestFirst_andOnlyResumableOnes() {
        Instant now = Instant.now();
        SessionDocument older = session("older", "p1", now.minusSeconds(120));
        SessionDocument newer = session("newer", "p2", now);
        SessionDocument closed = session("closed", "p3", now.plusSeconds(10));
        closed.setStatus(SessionStatus.CLOSED);
        SessionDocument bound = session("bound", "p4", now.plusSeconds(20));
        bound.setBoundConnectionId("other-editor");
        SessionDocument otherProfile = session("web", "p5", now.plusSeconds(30));
        otherProfile.setProfile("web");
        SessionDocument tenantProject = session("tenant", ProjectService.TENANT_PROJECT_NAME, now.plusSeconds(40));
        when(sessionService.listForUser(TENANT, USER))
                .thenReturn(List.of(older, closed, bound, otherProfile, tenantProject, newer));

        List<SessionDocument> candidates = targets.autoResumeCandidates(ctx);

        assertThat(candidates).extracting(SessionDocument::getSessionId).containsExactly("newer", "older");
    }

    @Test
    void projectOf_withoutProjectOrSession_routesByTheNewestCandidate() {
        Instant now = Instant.now();
        when(sessionService.listForUser(TENANT, USER))
                .thenReturn(List.of(session("older", "p1", now.minusSeconds(60)), session("newer", "p2", now)));

        assertThat(targets.projectOf(ctx, SessionBootstrapRequest.builder().build()))
                .contains("p2");
    }

    @Test
    void projectOf_skipsTheHomelessTenantProject_andFallsBackToTheHub() {
        // Routing must not pick a candidate the handler will refuse to resume.
        when(sessionService.listForUser(TENANT, USER))
                .thenReturn(List.of(session("tenant", ProjectService.TENANT_PROJECT_NAME, Instant.now())));

        assertThat(targets.projectOf(ctx, SessionBootstrapRequest.builder().build()))
                .contains(HomeBootstrapService.hubProjectName(USER));
    }

    @Test
    void projectOf_explicitProject_winsOverCandidates() {
        when(sessionService.listForUser(TENANT, USER)).thenReturn(List.of(session("s", "p1", Instant.now())));

        assertThat(targets.projectOf(
                        ctx,
                        SessionBootstrapRequest.builder().projectId("explicit").build()))
                .contains("explicit");
    }

    @Test
    void projectOf_explicitSession_isThatSessionsProject_orEmptyWhenUnknown() {
        when(sessionService.findBySessionId("known")).thenReturn(Optional.of(session("known", "p7", Instant.now())));
        when(sessionService.findBySessionId("unknown")).thenReturn(Optional.empty());

        assertThat(targets.projectOf(
                        ctx,
                        SessionBootstrapRequest.builder().sessionId("known").build()))
                .contains("p7");
        assertThat(targets.projectOf(
                        ctx,
                        SessionBootstrapRequest.builder().sessionId("unknown").build()))
                .isEmpty();
    }

    private static SessionDocument session(String sessionId, String projectId, Instant lastActivity) {
        SessionDocument doc = new SessionDocument();
        doc.setSessionId(sessionId);
        doc.setTenantId(TENANT);
        doc.setUserId(USER);
        doc.setProjectId(projectId);
        doc.setProfile(PROFILE);
        doc.setStatus(SessionStatus.IDLE);
        doc.setLastActivityAt(lastActivity);
        return doc;
    }
}
