package de.mhus.vance.brain.ws.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.SessionBootstrapRequest;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.SessionCreateRequest;
import de.mhus.vance.api.ws.SessionResumeRequest;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.project.ProjectManagerService;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.handlers.SessionBootstrapTargets;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HomePodLookupServiceTest {

    private ProjectManagerService projectManager;
    private SessionService sessionService;
    private HomePodLookupService lookup;
    private ConnectionContext ctx;

    @BeforeEach
    void setUp() {
        projectManager = mock(ProjectManagerService.class);
        sessionService = mock(SessionService.class);
        lookup = new HomePodLookupService(
                projectManager, sessionService, new ObjectMapper(), new SessionBootstrapTargets(sessionService));
        ctx = new ConnectionContext("acme", "alice", "Alice", "default", "1.0", "vance-foot", "conn-1", "10.0.0.1");
    }

    @Test
    void sessionCreate_localProject_returnsLocal() {
        when(projectManager.findProjectEndpoint("acme", "myproj")).thenReturn(Optional.of("pod-self:8080"));
        when(projectManager.isLocalPod("pod-self:8080")).thenReturn(true);

        HomePodTarget target = lookup.resolve(
                ctx,
                null,
                WebSocketEnvelope.request("r1", MessageType.SESSION_CREATE, new SessionCreateRequest("myproj")));

        assertThat(target.local()).isTrue();
    }

    @Test
    void sessionCreate_remoteProject_returnsRemote() {
        when(projectManager.findProjectEndpoint("acme", "myproj")).thenReturn(Optional.of("pod-7:8080"));
        when(projectManager.isLocalPod("pod-7:8080")).thenReturn(false);

        HomePodTarget target = lookup.resolve(
                ctx,
                null,
                WebSocketEnvelope.request("r1", MessageType.SESSION_CREATE, new SessionCreateRequest("myproj")));

        assertThat(target.local()).isFalse();
        assertThat(target.requireEndpoint()).isEqualTo("pod-7:8080");
    }

    @Test
    void sessionCreate_unknownProject_fallsBackToLocal() {
        when(projectManager.findProjectEndpoint(eq("acme"), eq("missing"))).thenReturn(Optional.empty());

        HomePodTarget target = lookup.resolve(
                ctx,
                null,
                WebSocketEnvelope.request("r1", MessageType.SESSION_CREATE, new SessionCreateRequest("missing")));

        assertThat(target.local()).isTrue();
    }

    @Test
    void sessionResume_resolvesViaSession() {
        SessionDocument doc = new SessionDocument();
        doc.setTenantId("acme");
        doc.setProjectId("myproj");
        when(sessionService.findBySessionId("sess-42")).thenReturn(Optional.of(doc));
        when(projectManager.findProjectEndpoint("acme", "myproj")).thenReturn(Optional.of("pod-9:8080"));
        when(projectManager.isLocalPod("pod-9:8080")).thenReturn(false);

        HomePodTarget target = lookup.resolve(
                ctx,
                null,
                WebSocketEnvelope.request(
                        "r2",
                        MessageType.SESSION_RESUME,
                        SessionResumeRequest.builder().sessionId("sess-42").build()));

        assertThat(target.local()).isFalse();
        assertThat(target.requireEndpoint()).isEqualTo("pod-9:8080");
    }

    @Test
    void otherType_usesBoundSessionFromCtx() {
        SessionDocument bound = new SessionDocument();
        bound.setSessionId("sess-77");
        bound.setTenantId("acme");
        bound.setProjectId("other");
        ConnectionContext boundCtx =
                new ConnectionContext("acme", "alice", "Alice", "default", "1.0", "vance-foot", "conn-1", "10.0.0.1");
        boundCtx.bindSession(bound);
        when(sessionService.findBySessionId("sess-77")).thenReturn(Optional.of(bound));
        when(projectManager.findProjectEndpoint("acme", "other")).thenReturn(Optional.of("pod-2:8080"));
        when(projectManager.isLocalPod("pod-2:8080")).thenReturn(false);

        HomePodTarget target = lookup.resolve(boundCtx, null, WebSocketEnvelope.request("r3", "process-create", null));

        assertThat(target.local()).isFalse();
        assertThat(target.requireEndpoint()).isEqualTo("pod-2:8080");
    }

    @Test
    void otherType_noBoundSession_envelopeSessionId_fallback() {
        SessionDocument doc = new SessionDocument();
        doc.setTenantId("acme");
        doc.setProjectId("p1");
        when(sessionService.findBySessionId("env-sess")).thenReturn(Optional.of(doc));
        when(projectManager.findProjectEndpoint("acme", "p1")).thenReturn(Optional.of("pod-self:8080"));
        when(projectManager.isLocalPod("pod-self:8080")).thenReturn(true);

        HomePodTarget target = lookup.resolve(ctx, "env-sess", WebSocketEnvelope.request("r4", "process-list", null));

        assertThat(target.local()).isTrue();
    }

    @Test
    void otherType_noSessionAnywhere_fallsBackToLocal() {
        HomePodTarget target = lookup.resolve(ctx, null, WebSocketEnvelope.request("r5", "process-list", null));

        assertThat(target.local()).isTrue();
    }

    @Test
    void sessionBootstrap_explicitProject_routesToItsHomePod() {
        when(projectManager.findProjectEndpoint("acme", "myproj")).thenReturn(Optional.of("pod-3:8080"));
        when(projectManager.isLocalPod("pod-3:8080")).thenReturn(false);

        HomePodTarget target = lookup.resolve(
                ctx,
                null,
                WebSocketEnvelope.request(
                        "r6",
                        MessageType.SESSION_BOOTSTRAP,
                        SessionBootstrapRequest.builder().projectId("myproj").build()));

        assertThat(target.requireEndpoint()).isEqualTo("pod-3:8080");
    }

    @Test
    void sessionBootstrap_explicitSession_routesToItsProjectsHomePod() {
        SessionDocument doc = new SessionDocument();
        doc.setTenantId("acme");
        doc.setProjectId("p7");
        when(sessionService.findBySessionId("sess-7")).thenReturn(Optional.of(doc));
        when(projectManager.findProjectEndpoint("acme", "p7")).thenReturn(Optional.of("pod-7:8080"));
        when(projectManager.isLocalPod("pod-7:8080")).thenReturn(false);

        HomePodTarget target = lookup.resolve(
                ctx,
                null,
                WebSocketEnvelope.request(
                        "r7",
                        MessageType.SESSION_BOOTSTRAP,
                        SessionBootstrapRequest.builder().sessionId("sess-7").build()));

        assertThat(target.requireEndpoint()).isEqualTo("pod-7:8080");
    }

    @Test
    void sessionBootstrap_noIds_routesToTheNewestResumableSession() {
        SessionDocument older = resumable("sess-old", "p-old", Instant.now().minusSeconds(600));
        SessionDocument newer = resumable("sess-new", "p-new", Instant.now());
        when(sessionService.listForUser("acme", "alice")).thenReturn(List.of(older, newer));
        when(projectManager.findProjectEndpoint("acme", "p-new")).thenReturn(Optional.of("pod-5:8080"));
        when(projectManager.isLocalPod("pod-5:8080")).thenReturn(false);

        HomePodTarget target = lookup.resolve(
                ctx,
                null,
                WebSocketEnvelope.request(
                        "r8",
                        MessageType.SESSION_BOOTSTRAP,
                        SessionBootstrapRequest.builder().build()));

        assertThat(target.requireEndpoint()).isEqualTo("pod-5:8080");
    }

    @Test
    void sessionBootstrap_noIdsAndNothingToResume_routesToTheCallersHub() {
        String hub = HomeBootstrapService.hubProjectName("alice");
        when(sessionService.listForUser("acme", "alice")).thenReturn(List.of());
        when(projectManager.findProjectEndpoint("acme", hub)).thenReturn(Optional.of("pod-4:8080"));
        when(projectManager.isLocalPod("pod-4:8080")).thenReturn(false);

        HomePodTarget target = lookup.resolve(
                ctx,
                null,
                WebSocketEnvelope.request(
                        "r9",
                        MessageType.SESSION_BOOTSTRAP,
                        SessionBootstrapRequest.builder().build()));

        assertThat(target.requireEndpoint()).isEqualTo("pod-4:8080");
    }

    private static SessionDocument resumable(String sessionId, String projectId, Instant lastActivity) {
        SessionDocument doc = new SessionDocument();
        doc.setSessionId(sessionId);
        doc.setTenantId("acme");
        doc.setUserId("alice");
        doc.setProjectId(projectId);
        doc.setProfile("default");
        doc.setLastActivityAt(lastActivity);
        return doc;
    }
}
