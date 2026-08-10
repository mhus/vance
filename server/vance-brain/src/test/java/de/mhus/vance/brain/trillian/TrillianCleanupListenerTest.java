package de.mhus.vance.brain.trillian;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.session.SessionLifecycleService;
import de.mhus.vance.shared.permission.PermissionBootstrap;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessStatusChangedEvent;
import de.mhus.vance.shared.user.UserService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrillianCleanupListenerTest {

    private static final String TENANT = "acme";
    private static final String TRILLIAN = "_trillian-03725";
    private static final String PEER_SESSION = "sess_peer";

    @Mock
    ThinkProcessService thinkProcessService;
    @Mock
    UserService userService;
    @Mock
    SessionLifecycleService sessionLifecycleService;
    @Mock
    PermissionBootstrap permissionBootstrap;
    @Mock
    ObjectProvider<PermissionBootstrap> permissionBootstrapProvider;

    private TrillianCleanupListener listener() {
        return new TrillianCleanupListener(thinkProcessService, userService,
                sessionLifecycleService, permissionBootstrapProvider);
    }

    @Test
    void controlProcessClosed_revokesGrantsBeforeDeletingTheAccount() {
        givenControlProcess();
        givenProviderAvailable();

        listener().onProcessStatusChanged(closedEvent());

        // Order matters: a grant must never outlive its subject, not even
        // for the window between the two calls.
        InOrder order = inOrder(permissionBootstrap, userService);
        order.verify(permissionBootstrap).revokeAll(TENANT, TRILLIAN);
        order.verify(userService).delete(TENANT, TRILLIAN);
    }

    @Test
    void controlProcessClosed_withoutGrantProvider_stillDeletesTheAccount() {
        givenControlProcess();
        // ifAvailable stays a no-op — e.g. an external governor owns rights.

        listener().onProcessStatusChanged(closedEvent());

        verify(userService).delete(TENANT, TRILLIAN);
    }

    @Test
    void revokeFailure_doesNotBlockAccountDeletion() {
        givenControlProcess();
        givenProviderAvailable();
        doThrow(new IllegalStateException("grant storage down"))
                .when(permissionBootstrap).revokeAll(TENANT, TRILLIAN);

        listener().onProcessStatusChanged(closedEvent());

        verify(userService).delete(TENANT, TRILLIAN);
    }

    @Test
    void nonTrillianProcessClosed_touchesNeitherGrantsNorAccount() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setThinkEngine("arthur");
        when(thinkProcessService.findById("p1")).thenReturn(Optional.of(process));
        givenProviderAvailable();

        listener().onProcessStatusChanged(closedEvent());

        verify(permissionBootstrap, never()).revokeAll(any(), any());
        verify(userService, never()).delete(any(), any());
    }

    @Test
    void nonTerminalStatusChange_isIgnored() {
        givenProviderAvailable();

        listener().onProcessStatusChanged(new ThinkProcessStatusChangedEvent(
                "p1", TENANT, "sess_control", null,
                ThinkProcessStatus.RUNNING, ThinkProcessStatus.IDLE));

        verify(permissionBootstrap, never()).revokeAll(any(), any());
        verify(userService, never()).delete(any(), any());
    }

    private void givenControlProcess() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setThinkEngine(TrillianSessionBootstrapper.CONTROL_ENGINE_NAME);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(TrillianSessionBootstrapper.PARAM_TRILLIAN_USER_NAME, TRILLIAN);
        params.put(TrillianSessionBootstrapper.PARAM_PEER_SESSION_ID, PEER_SESSION);
        process.setEngineParams(params);
        when(thinkProcessService.findById("p1")).thenReturn(Optional.of(process));
    }

    @SuppressWarnings("unchecked")
    private void givenProviderAvailable() {
        doAnswer(invocation -> {
            ((Consumer<PermissionBootstrap>) invocation.getArgument(0))
                    .accept(permissionBootstrap);
            return null;
        }).when(permissionBootstrapProvider).ifAvailable(any());
    }

    private static ThinkProcessStatusChangedEvent closedEvent() {
        return new ThinkProcessStatusChangedEvent(
                "p1", TENANT, "sess_control", null,
                ThinkProcessStatus.RUNNING, ThinkProcessStatus.CLOSED);
    }
}
