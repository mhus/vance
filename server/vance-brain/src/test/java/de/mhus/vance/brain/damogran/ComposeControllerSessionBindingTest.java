package de.mhus.vance.brain.damogran;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.tools.exec.ExecManager;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Which process a REST compose run binds to.
 *
 * <p>Running under a session's chat process re-points that agent's
 * WorkTarget and registers it as exec owner, which wakes it with an
 * {@code EXEC_FINISHED} turn. Tenant and project alone were not enough to
 * pick one: a colleague's private session is not a carrier the caller may
 * borrow.
 */
class ComposeControllerSessionBindingTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "proj";
    private static final String CALLER = "bob";

    private final DamogranComposeService composeService = mock(DamogranComposeService.class);
    private final DamogranManifestParser manifestParser = mock(DamogranManifestParser.class);
    private final DocumentService documentService = mock(DocumentService.class);
    private final SessionService sessionService = mock(SessionService.class);
    private final DamogranProcessResolver processResolver = mock(DamogranProcessResolver.class);
    private final ComposeRunRegistry runRegistry = mock(ComposeRunRegistry.class);
    private final ExecManager execManager = mock(ExecManager.class);
    private final RequestAuthority authority = mock(RequestAuthority.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    private final ComposeController controller = new ComposeController(
            composeService, manifestParser, documentService, sessionService,
            processResolver, runRegistry, execManager, authority);

    @BeforeEach
    void setUp() {
        when(request.getAttribute(AccessFilterBase.ATTR_USERNAME)).thenReturn(CALLER);
        // A manifest without a session: section — process-less unless a
        // sessionId names a carrier the caller may use.
        when(manifestParser.parse(any())).thenReturn(new DamogranManifest(
                new DamogranManifest.WorkspaceSpec(
                        "ws", DamogranManifest.WorkspaceSpec.DEFAULT_TYPE,
                        false, false, java.util.Map.of(),
                        DamogranManifest.WorkspaceSpec.DEFAULT_TARGET),
                List.of(), List.of(), List.of(), null, null,
                DamogranManifest.SessionSpec.DISABLED));
        // Terminal before the controller waits on it — the fast-path budget
        // would otherwise hold this test for half a minute.
        ComposeRun finished = new ComposeRun("cr-1", TENANT, PROJECT, "ws", Instant.now());
        finished.complete(new DamogranComposeResult(DamogranStatus.SUCCESS, "ws", List.of(), null));
        when(composeService.runAsync(any(), any(), any(), any(DamogranManifest.class),
                any(), any(), any()))
                .thenReturn(finished);
    }

    @Test
    void run_doesNotBorrowThePrivateSessionOfAnotherUser() {
        when(sessionService.findBySessionId("sess-alice"))
                .thenReturn(Optional.of(session("alice", false)));

        controller.run(TENANT, request("sess-alice"), request);

        assertThat(boundProcessId()).isNull();
    }

    @Test
    void run_bindsToTheCallersOwnSession() {
        when(sessionService.findBySessionId("sess-bob"))
                .thenReturn(Optional.of(session(CALLER, false)));

        controller.run(TENANT, request("sess-bob"), request);

        assertThat(boundProcessId()).isEqualTo("chat-of-" + CALLER);
    }

    @Test
    void run_bindsToASharedSessionOfAnotherUser() {
        // allowMultipleClients is the owner's own invitation — the compose
        // shares the workspace with a conversation the caller is part of.
        when(sessionService.findBySessionId("sess-alice"))
                .thenReturn(Optional.of(session("alice", true)));

        controller.run(TENANT, request("sess-alice"), request);

        assertThat(boundProcessId()).isEqualTo("chat-of-alice");
    }

    private @org.jspecify.annotations.Nullable String boundProcessId() {
        ArgumentCaptor<String> processId = ArgumentCaptor.captor();
        org.mockito.Mockito.verify(composeService).runAsync(
                eq(TENANT), eq(PROJECT), processId.capture(),
                any(DamogranManifest.class), any(), any(), any());
        return processId.getValue();
    }

    private static ComposeController.RunRequest request(String sessionId) {
        return new ComposeController.RunRequest(
                null, "workspace:\n  name: ws\n", null, PROJECT, sessionId, null);
    }

    private static SessionDocument session(String owner, boolean shared) {
        return SessionDocument.builder()
                .sessionId("sess-" + owner)
                .tenantId(TENANT)
                .projectId(PROJECT)
                .userId(owner)
                .allowMultipleClients(shared)
                .chatProcessId("chat-of-" + owner)
                .build();
    }
}
