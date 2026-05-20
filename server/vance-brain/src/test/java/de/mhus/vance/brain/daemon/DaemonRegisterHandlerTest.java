package de.mhus.vance.brain.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.tools.DaemonRegisterRequest;
import de.mhus.vance.api.tools.ToolSpec;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.Profiles;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.servertool.ServerToolRegistry;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates the WS-handler contract: only profile=daemon may execute,
 * user-projects are rejected, name pattern is enforced, and registry
 * collisions surface as HTTP 409.
 */
class DaemonRegisterHandlerTest {

    private DaemonRegistry registry;
    private ProjectService projects;
    private WebSocketSender sender;
    private ServerToolRegistry serverTools;
    private DaemonRegisterHandler handler;
    private WebSocketSession ws;

    @BeforeEach
    void setUp() {
        registry = new DaemonRegistry();
        projects = mock(ProjectService.class);
        sender = mock(WebSocketSender.class);
        serverTools = mock(ServerToolRegistry.class);
        handler = new DaemonRegisterHandler(
                JsonMapper.builder().build(), sender, registry, projects, serverTools);
        ws = mock(WebSocketSession.class);
    }

    @Test
    void canExecute_acceptsDaemonProfile_rejectsOthers() {
        assertThat(handler.canExecute(ctxWith(Profiles.DAEMON))).isTrue();
        assertThat(handler.canExecute(ctxWith(Profiles.FOOT))).isFalse();
        assertThat(handler.canExecute(ctxWith(Profiles.WEB))).isFalse();
    }

    @Test
    void handle_registersValidRequest() throws Exception {
        when(projects.findByTenantAndName("acme", "ops"))
                .thenReturn(Optional.of(new ProjectDocument()));
        DaemonRegisterRequest req = DaemonRegisterRequest.builder()
                .projectId("ops")
                .daemonName("server-prod-01")
                .tools(List.of(ToolSpec.builder().name("client_exec_run").build()))
                .build();

        handler.handle(ctxWith(Profiles.DAEMON), ws, envelopeOf(req));

        assertThat(registry.find("acme", "ops", "server-prod-01")).isPresent();
        verify(sender).sendReply(eq(ws), any(), eq(MessageType.DAEMON_REGISTER), eq(null));
    }

    @Test
    void handle_rejectsUserProject() throws Exception {
        DaemonRegisterRequest req = DaemonRegisterRequest.builder()
                .projectId("_user_wile.coyote")
                .daemonName("desktop")
                .build();

        handler.handle(ctxWith(Profiles.DAEMON), ws, envelopeOf(req));

        verify(sender).sendError(eq(ws), any(), eq(400), contains("user-scoped"));
        assertThat(registry.size()).isZero();
    }

    @Test
    void handle_acceptsTenantProjectWithoutLookup() throws Exception {
        DaemonRegisterRequest req = DaemonRegisterRequest.builder()
                .projectId(HomeBootstrapService.TENANT_PROJECT_NAME)
                .daemonName("tenant-wide")
                .build();

        handler.handle(ctxWith(Profiles.DAEMON), ws, envelopeOf(req));

        verify(projects, never()).findByTenantAndName(any(), any());
        assertThat(registry.find("acme", HomeBootstrapService.TENANT_PROJECT_NAME, "tenant-wide"))
                .isPresent();
    }

    @Test
    void handle_rejectsUnknownProject() throws Exception {
        when(projects.findByTenantAndName("acme", "ghost")).thenReturn(Optional.empty());
        DaemonRegisterRequest req = DaemonRegisterRequest.builder()
                .projectId("ghost")
                .daemonName("x")
                .build();

        handler.handle(ctxWith(Profiles.DAEMON), ws, envelopeOf(req));

        verify(sender).sendError(eq(ws), any(), eq(404), contains("does not exist"));
        assertThat(registry.size()).isZero();
    }

    @Test
    void handle_rejectsInvalidDaemonName() throws Exception {
        when(projects.findByTenantAndName("acme", "ops"))
                .thenReturn(Optional.of(new ProjectDocument()));
        DaemonRegisterRequest req = DaemonRegisterRequest.builder()
                .projectId("ops")
                .daemonName("Has UPPER and spaces")
                .build();

        handler.handle(ctxWith(Profiles.DAEMON), ws, envelopeOf(req));

        verify(sender).sendError(eq(ws), any(), eq(400), contains("daemonName"));
        assertThat(registry.size()).isZero();
    }

    @Test
    void handle_collisionReturns409() throws Exception {
        when(projects.findByTenantAndName("acme", "ops"))
                .thenReturn(Optional.of(new ProjectDocument()));
        // Pre-register on a different session.
        registry.register(
                new DaemonRegistry.DaemonKey("acme", "ops", "x"),
                mock(WebSocketSession.class),
                List.of());

        DaemonRegisterRequest req = DaemonRegisterRequest.builder()
                .projectId("ops").daemonName("x").build();
        handler.handle(ctxWith(Profiles.DAEMON), ws, envelopeOf(req));

        verify(sender).sendError(eq(ws), any(), eq(409), contains("already registered"));
    }

    @Test
    void handle_blankProjectIdReturns400() throws Exception {
        DaemonRegisterRequest req = DaemonRegisterRequest.builder()
                .projectId(" ").daemonName("x").build();
        handler.handle(ctxWith(Profiles.DAEMON), ws, envelopeOf(req));
        verify(sender).sendError(eq(ws), any(), anyInt(), contains("projectId"));
    }

    // ──────────────────── helpers ────────────────────

    private static ConnectionContext ctxWith(String profile) {
        return new ConnectionContext(
                "acme", "_daemon-user", "Daemon User", profile,
                "0.1.0", "test-client", "conn-1", "127.0.0.1");
    }

    private static WebSocketEnvelope envelopeOf(Object data) {
        return WebSocketEnvelope.request("req-1", MessageType.DAEMON_REGISTER, data);
    }
}
