package de.mhus.vance.brain.ws.handlers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.session.SessionStatus;
import de.mhus.vance.api.thinkprocess.SessionBootstrapRequest;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.action.ActionExecutorRegistry;
import de.mhus.vance.brain.events.SessionConnectionRegistry;
import de.mhus.vance.brain.events.SessionRosterBroadcaster;
import de.mhus.vance.brain.inbox.InboxPendingSummaryPusher;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.progress.ProcessCountsPusher;
import de.mhus.vance.brain.project.ProjectLifecycleService;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.session.SessionChatBootstrapper;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins "a session bootstrap brings its project" for all three entry shapes
 * (explicit resume / auto-resume / create), and the one ordering decision that
 * is easy to get wrong.
 *
 * <h2>Why this exists</h2>
 * <p>All three paths used to take a bare {@code claimForLocalPod}. Since the
 * project-ownership lease rework a claim makes the pod the owner without
 * activating the project, and the hook / scheduler document listeners are
 * activation-gated — so the pod ended up owning a project's hooks and
 * schedulers while running none of them, with no error anywhere.
 * {@code ProjectClaimActivationContractTest} keeps future call sites honest at
 * the source level; this one pins the behaviour of the three that exist.
 *
 * <h2>The ordering</h2>
 * <p>{@code bring} clears every stale session binding of the project
 * ({@code SessionService.unbindAllForProjects}). On the auto-resume path the
 * bind therefore has to come <em>after</em> the bring — the previous shape
 * (bind, then claim) would, with a plain {@code claim → bring} substitution,
 * have had the activation sweep away the bind it just took.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionBootstrapHandlerActivationTest {

    private static final String TENANT = "acme";
    private static final String USER = "wile.coyote";
    private static final String PROFILE = "foot";
    private static final String PROJECT = "roadrunner";
    private static final String SESSION = "sess-1";

    @Mock private WebSocketSender sender;
    @Mock private SessionService sessionService;
    @Mock private ProjectService projectService;
    @Mock private ProjectLifecycleService lifecycleService;
    @Mock private ThinkProcessService thinkProcessService;
    @Mock private ThinkEngineService thinkEngineService;
    @Mock private SessionConnectionRegistry connectionRegistry;
    @Mock private SessionRosterBroadcaster rosterBroadcaster;
    @Mock private SessionChatBootstrapper chatBootstrapper;
    @Mock private InboxPendingSummaryPusher inboxSummaryPusher;
    @Mock private ProcessCountsPusher processCountsPusher;
    @Mock private HomeBootstrapService homeBootstrapService;
    @Mock private RequestAuthority authority;
    @Mock private ActionExecutorRegistry actionRegistry;
    @Mock private LaneScheduler laneScheduler;

    private SessionBootstrapHandler handler;
    private WebSocketSession wsSession;
    private ConnectionContext ctx;

    @BeforeEach
    void setUp() {
        handler = new SessionBootstrapHandler(
                JsonMapper.builder().build(), sender, sessionService, projectService,
                lifecycleService, thinkProcessService, thinkEngineService,
                connectionRegistry, rosterBroadcaster, chatBootstrapper,
                inboxSummaryPusher, processCountsPusher, homeBootstrapService,
                authority, actionRegistry, laneScheduler);
        wsSession = mock(WebSocketSession.class);
        ctx = new ConnectionContext(
                TENANT, USER, "Wile", PROFILE, "1.0", "cli", "ed-1", "10.0.0.1");

        // Everything after the session step is out of scope here — stub just
        // enough that handle() runs to completion instead of NPE-ing.
        when(connectionRegistry.register(
                anyString(), anyString(), anyString(), any(), any(), anyBoolean()))
                .thenReturn(SessionConnectionRegistry.RegisterResult.accepted());
        when(chatBootstrapper.ensureChatProcess(any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    void explicitResume_bringsTheProject() throws Exception {
        SessionDocument existing = session(SESSION, PROJECT);
        when(sessionService.findBySessionId(SESSION)).thenReturn(Optional.of(existing));
        when(sessionService.tryBindWithUserTakeover(eq(SESSION), anyString())).thenReturn(true);
        when(connectionRegistry.findForUser(SESSION, USER)).thenReturn(Optional.empty());

        handler.handle(ctx, wsSession, envelope(
                SessionBootstrapRequest.builder().sessionId(SESSION).build()));

        verify(lifecycleService).bring(TENANT, PROJECT);
    }

    @Test
    void explicitCreate_bringsTheProject() throws Exception {
        when(homeBootstrapService.resolveOrAutoProvision(TENANT, PROJECT))
                .thenReturn(Optional.of(project(PROJECT)));
        when(sessionService.create(anyString(), anyString(), anyString(),
                any(), any(), any(), any()))
                .thenReturn(session(SESSION, PROJECT));
        when(sessionService.tryBind(eq(SESSION), anyString())).thenReturn(true);

        handler.handle(ctx, wsSession, envelope(
                SessionBootstrapRequest.builder().projectId(PROJECT).build()));

        verify(lifecycleService).bring(TENANT, PROJECT);
    }

    @Test
    void autoResume_bringsTheProjectBeforeTakingTheBind() throws Exception {
        SessionDocument candidate = session(SESSION, PROJECT);
        candidate.setLastActivityAt(Instant.now());
        when(sessionService.listForUser(TENANT, USER)).thenReturn(List.of(candidate));
        when(sessionService.tryBind(eq(SESSION), anyString())).thenReturn(true);

        handler.handle(ctx, wsSession, envelope(
                SessionBootstrapRequest.builder().build()));

        // The load-bearing assertion: bring's stale-bind sweep must run before
        // the bind, or it clears the very binding this bootstrap just took.
        InOrder order = inOrder(lifecycleService, sessionService);
        order.verify(lifecycleService).bring(TENANT, PROJECT);
        order.verify(sessionService).tryBind(eq(SESSION), anyString());
    }

    private static SessionDocument session(String sessionId, String projectId) {
        SessionDocument doc = new SessionDocument();
        doc.setSessionId(sessionId);
        doc.setTenantId(TENANT);
        doc.setUserId(USER);
        doc.setProjectId(projectId);
        doc.setProfile(PROFILE);
        doc.setStatus(SessionStatus.IDLE);
        return doc;
    }

    private static ProjectDocument project(String name) {
        ProjectDocument doc = new ProjectDocument();
        doc.setTenantId(TENANT);
        doc.setName(name);
        return doc;
    }

    private static WebSocketEnvelope envelope(SessionBootstrapRequest request) {
        WebSocketEnvelope env = new WebSocketEnvelope();
        env.setType(MessageType.SESSION_BOOTSTRAP);
        env.setId("env-1");
        env.setData(JsonMapper.builder().build().convertValue(request, Object.class));
        return env;
    }
}
