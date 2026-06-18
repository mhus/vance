package de.mhus.vance.brain.ws.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.SessionCreateRequest;
import de.mhus.vance.api.ws.SessionResumeRequest;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.project.ProjectManagerService;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
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
        lookup = new HomePodLookupService(projectManager, sessionService, new ObjectMapper());
        ctx = new ConnectionContext(
                "acme", "alice", "Alice", "default", "1.0", "vance-foot",
                "conn-1", "10.0.0.1");
    }

    @Test
    void sessionCreate_localProject_returnsLocal() {
        when(projectManager.findProjectEndpoint("acme", "myproj"))
                .thenReturn(Optional.of("pod-self:8080"));
        when(projectManager.isLocalPod("pod-self:8080")).thenReturn(true);

        HomePodTarget target = lookup.resolve(
                ctx,
                null,
                WebSocketEnvelope.request("r1", MessageType.SESSION_CREATE,
                        new SessionCreateRequest("myproj")));

        assertThat(target.local()).isTrue();
    }

    @Test
    void sessionCreate_remoteProject_returnsRemote() {
        when(projectManager.findProjectEndpoint("acme", "myproj"))
                .thenReturn(Optional.of("pod-7:8080"));
        when(projectManager.isLocalPod("pod-7:8080")).thenReturn(false);

        HomePodTarget target = lookup.resolve(
                ctx,
                null,
                WebSocketEnvelope.request("r1", MessageType.SESSION_CREATE,
                        new SessionCreateRequest("myproj")));

        assertThat(target.local()).isFalse();
        assertThat(target.requireEndpoint()).isEqualTo("pod-7:8080");
    }

    @Test
    void sessionCreate_unknownProject_fallsBackToLocal() {
        when(projectManager.findProjectEndpoint(eq("acme"), eq("missing")))
                .thenReturn(Optional.empty());

        HomePodTarget target = lookup.resolve(
                ctx,
                null,
                WebSocketEnvelope.request("r1", MessageType.SESSION_CREATE,
                        new SessionCreateRequest("missing")));

        assertThat(target.local()).isTrue();
    }

    @Test
    void sessionResume_resolvesViaSession() {
        SessionDocument doc = new SessionDocument();
        doc.setTenantId("acme");
        doc.setProjectId("myproj");
        when(sessionService.findBySessionId("sess-42")).thenReturn(Optional.of(doc));
        when(projectManager.findProjectEndpoint("acme", "myproj"))
                .thenReturn(Optional.of("pod-9:8080"));
        when(projectManager.isLocalPod("pod-9:8080")).thenReturn(false);

        HomePodTarget target = lookup.resolve(
                ctx,
                null,
                WebSocketEnvelope.request("r2", MessageType.SESSION_RESUME,
                        new SessionResumeRequest("sess-42")));

        assertThat(target.local()).isFalse();
        assertThat(target.requireEndpoint()).isEqualTo("pod-9:8080");
    }

    @Test
    void otherType_usesBoundSessionFromCtx() {
        SessionDocument bound = new SessionDocument();
        bound.setSessionId("sess-77");
        bound.setTenantId("acme");
        bound.setProjectId("other");
        ConnectionContext boundCtx = new ConnectionContext(
                "acme", "alice", "Alice", "default", "1.0", "vance-foot",
                "conn-1", "10.0.0.1");
        boundCtx.bindSession(bound);
        when(sessionService.findBySessionId("sess-77")).thenReturn(Optional.of(bound));
        when(projectManager.findProjectEndpoint("acme", "other"))
                .thenReturn(Optional.of("pod-2:8080"));
        when(projectManager.isLocalPod("pod-2:8080")).thenReturn(false);

        HomePodTarget target = lookup.resolve(
                boundCtx,
                null,
                WebSocketEnvelope.request("r3", "process-create", null));

        assertThat(target.local()).isFalse();
        assertThat(target.requireEndpoint()).isEqualTo("pod-2:8080");
    }

    @Test
    void otherType_noBoundSession_envelopeSessionId_fallback() {
        SessionDocument doc = new SessionDocument();
        doc.setTenantId("acme");
        doc.setProjectId("p1");
        when(sessionService.findBySessionId("env-sess")).thenReturn(Optional.of(doc));
        when(projectManager.findProjectEndpoint("acme", "p1"))
                .thenReturn(Optional.of("pod-self:8080"));
        when(projectManager.isLocalPod("pod-self:8080")).thenReturn(true);

        HomePodTarget target = lookup.resolve(
                ctx,
                "env-sess",
                WebSocketEnvelope.request("r4", "process-list", null));

        assertThat(target.local()).isTrue();
    }

    @Test
    void otherType_noSessionAnywhere_fallsBackToLocal() {
        HomePodTarget target = lookup.resolve(
                ctx,
                null,
                WebSocketEnvelope.request("r5", "process-list", null));

        assertThat(target.local()).isTrue();
    }
}
