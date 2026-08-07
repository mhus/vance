package de.mhus.vance.brain.ws.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.command.ProcessCommandRequest;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.command.EngineCommandDispatcher;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

/**
 * Inbound guard of the {@code //verb} control-plane channel: payload
 * validation, target resolution and the {@code EXECUTE} enforcement that
 * stands between any session participant and an engine command.
 *
 * <p>The lane hand-off itself is not exercised — {@link LaneScheduler} is
 * a mock here, so these tests pin exactly what the receive thread decides
 * before anything is scheduled.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProcessCommandHandlerTest {

    private static final String TENANT = "acme";
    private static final String SESSION = "sess-1";
    private static final String PROC_ID = "proc-1";
    private static final String PROC_NAME = "main";

    @Mock private WebSocketSender sender;
    @Mock private ThinkProcessService thinkProcessService;
    @Mock private LaneScheduler laneScheduler;
    @Mock private EngineCommandDispatcher dispatcher;
    @Mock private RequestAuthority authority;

    private ProcessCommandHandler handler;
    private WebSocketSession wsSession;

    @BeforeEach
    void setUp() {
        handler = new ProcessCommandHandler(
                JsonMapper.builder().build(), sender, thinkProcessService,
                laneScheduler, dispatcher, authority);
        wsSession = mock(WebSocketSession.class);
    }

    private ConnectionContext boundContext() {
        ConnectionContext ctx = new ConnectionContext(
                TENANT, "wile.coyote", "Wile", "foot", "1.0", "cli", "ed-1", "10.0.0.1");
        SessionDocument session = new SessionDocument();
        session.setSessionId(SESSION);
        session.setTenantId(TENANT);
        session.setProjectId("proj");
        ctx.bindSession(session);
        return ctx;
    }

    private static WebSocketEnvelope envelope(Object data) {
        return WebSocketEnvelope.request("req-1", MessageType.PROCESS_COMMAND, data);
    }

    private static ProcessCommandRequest request(String processName, String command) {
        return ProcessCommandRequest.builder()
                .processName(processName)
                .command(command)
                .params(Map.of())
                .build();
    }

    private ThinkProcessDocument process() {
        ThinkProcessDocument doc = new ThinkProcessDocument();
        doc.setId(PROC_ID);
        doc.setName(PROC_NAME);
        doc.setTenantId(TENANT);
        doc.setProjectId("proj");
        doc.setSessionId(SESSION);
        return doc;
    }

    @Test
    void missingCommand_isRejectedAsBadRequest() throws Exception {
        handler.handle(boundContext(), wsSession, envelope(request(PROC_NAME, "  ")));

        verify(sender).sendError(eq(wsSession), any(), eq(400), anyString());
        verify(laneScheduler, never()).submit(anyString(), any(Runnable.class));
    }

    @Test
    void unknownProcess_is404AndNeverReachesTheLane() throws Exception {
        when(thinkProcessService.findByName(TENANT, SESSION, "ghost"))
                .thenReturn(Optional.empty());

        handler.handle(boundContext(), wsSession, envelope(request("ghost", "ping")));

        verify(sender).sendError(eq(wsSession), any(), eq(404), anyString());
        verify(laneScheduler, never()).submit(anyString(), any(Runnable.class));
    }

    @Test
    void enforcesExecuteOnTheTargetProcess() throws Exception {
        when(thinkProcessService.findByName(TENANT, SESSION, PROC_NAME))
                .thenReturn(Optional.of(process()));

        handler.handle(boundContext(), wsSession, envelope(request(PROC_NAME, "ping")));

        verify(authority).enforce(
                any(ConnectionContext.class),
                eq(new Resource.ThinkProcess(TENANT, "proj", SESSION, PROC_ID)),
                eq(Action.EXECUTE));
    }

    @Test
    void deniedExecute_stopsBeforeTheLaneHandOff() {
        when(thinkProcessService.findByName(TENANT, SESSION, PROC_NAME))
                .thenReturn(Optional.of(process()));
        doThrow(new PermissionDeniedException(
                SecurityContext.SYSTEM,
                new Resource.ThinkProcess(TENANT, "proj", SESSION, PROC_ID),
                Action.EXECUTE))
                .when(authority).enforce(any(ConnectionContext.class), any(), any());

        ConnectionContext ctx = boundContext();
        assertThatThrownBy(() -> handler.handle(ctx, wsSession, envelope(request(PROC_NAME, "ping"))))
                .isInstanceOf(PermissionDeniedException.class);

        verify(laneScheduler, never()).submit(anyString(), any(Runnable.class));
    }

    @Test
    void authorisedCommand_isHandedToTheProcessLane() throws Exception {
        when(thinkProcessService.findByName(TENANT, SESSION, PROC_NAME))
                .thenReturn(Optional.of(process()));

        handler.handle(boundContext(), wsSession, envelope(request(PROC_NAME, "ping")));

        verify(laneScheduler).submit(eq(PROC_ID), any(Runnable.class));
        verify(sender, never()).sendError(any(), any(), anyInt(), anyString());
    }

    @Test
    void unboundConnection_cannotAddressAProcess() throws Exception {
        ConnectionContext unbound = new ConnectionContext(
                TENANT, "wile.coyote", "Wile", "foot", "1.0", "cli", "ed-1", "10.0.0.1");

        handler.handle(unbound, wsSession, envelope(request(PROC_NAME, "ping")));

        verify(sender).sendError(eq(wsSession), any(), eq(500), anyString());
        verify(laneScheduler, never()).submit(anyString(), any(Runnable.class));
    }

    @Test
    void typeIsTheProcessCommandMessage() {
        assertThat(handler.type()).isEqualTo(MessageType.PROCESS_COMMAND);
    }
}
