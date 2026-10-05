package de.mhus.vance.brain.trillian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.insights.TrillianInsightsDto;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The read model of a Trillian pair for the insights view: the control
 * side carries the user holding the session, the worker side carries the
 * loop's last run. Both are cosmetic — a gone session, a gone user or a
 * loop that never turned must not stop the state view from answering.
 */
@ExtendWith(MockitoExtension.class)
class TrillianInsightsServiceTest {

    private static final String TENANT = "acme";
    private static final String SESSION = "sess_1";
    private static final String USER = "alice";
    private static final String CONTROL_PROCESS_ID = "proc-1";
    private static final String PEER_PROCESS_ID = "proc-2";

    @Mock
    ThinkProcessService thinkProcessService;

    @Mock
    SessionService sessionService;

    @Mock
    UserService userService;

    @Mock
    ChatMessageService chatMessageService;

    @Mock
    TrillianInternalApi api;

    @Mock
    TrillianActivationGate activationGate;

    TrillianInsightsService service;

    // Built in @BeforeEach, not in a field initializer: the mock is injected
    // after construction, so an initializer would capture null.
    @BeforeEach
    void setUp() {
        service = new TrillianInsightsService(
                thinkProcessService, sessionService, userService, chatMessageService, api, activationGate);
    }

    @Test
    void controlCarriesTheSessionUser() {
        givenControlProcess();
        givenNoPeer();
        givenSession(USER);
        when(userService.findByTenantAndName(TENANT, USER))
                .thenReturn(Optional.of(
                        UserDocument.builder().name(USER).title("Alice A.").build()));

        TrillianInsightsDto dto = service.describe(TENANT, CONTROL_PROCESS_ID).orElseThrow();

        assertThat(dto.getControl().getUserId()).isEqualTo(USER);
        assertThat(dto.getControl().getUserTitle()).isEqualTo("Alice A.");
    }

    @Test
    void goneUserLeavesTheTitleUnset() {
        givenControlProcess();
        givenNoPeer();
        givenSession(USER);
        when(userService.findByTenantAndName(TENANT, USER)).thenReturn(Optional.empty());

        TrillianInsightsDto dto = service.describe(TENANT, CONTROL_PROCESS_ID).orElseThrow();

        assertThat(dto.getControl().getUserId()).isEqualTo(USER);
        assertThat(dto.getControl().getUserTitle()).isNull();
    }

    @Test
    void goneSessionLeavesTheUserUnset() {
        givenControlProcess();
        givenNoPeer();
        when(sessionService.findBySessionId(SESSION)).thenReturn(Optional.empty());

        TrillianInsightsDto dto = service.describe(TENANT, CONTROL_PROCESS_ID).orElseThrow();

        assertThat(dto.getControl().getUserId()).isNull();
        assertThat(dto.getControl().getUserTitle()).isNull();
    }

    @Test
    void workerCarriesTheLastRun() {
        givenControlProcess();
        givenPeer();
        Instant lastRun = Instant.parse("2026-10-04T20:15:00Z");
        when(chatMessageService.findLatestCreatedAt(TENANT, PEER_PROCESS_ID)).thenReturn(Optional.of(lastRun));

        TrillianInsightsDto dto = service.describe(TENANT, CONTROL_PROCESS_ID).orElseThrow();

        assertThat(dto.getWorker()).isNotNull();
        assertThat(dto.getWorker().getLastRunAt()).isEqualTo(lastRun);
    }

    @Test
    void loopThatNeverTurnedLeavesTheLastRunUnset() {
        givenControlProcess();
        givenPeer();
        when(chatMessageService.findLatestCreatedAt(TENANT, PEER_PROCESS_ID)).thenReturn(Optional.empty());

        TrillianInsightsDto dto = service.describe(TENANT, CONTROL_PROCESS_ID).orElseThrow();

        assertThat(dto.getWorker()).isNotNull();
        assertThat(dto.getWorker().getLastRunAt()).isNull();
    }

    private void givenControlProcess() {
        when(thinkProcessService.findById(CONTROL_PROCESS_ID))
                .thenReturn(Optional.of(ThinkProcessDocument.builder()
                        .id(CONTROL_PROCESS_ID)
                        .tenantId(TENANT)
                        .projectId("proj")
                        .sessionId(SESSION)
                        .name("control")
                        .thinkEngine(TrillianSessionBootstrapper.CONTROL_ENGINE_NAME)
                        .status(ThinkProcessStatus.RUNNING)
                        .build()));
    }

    private void givenNoPeer() {
        // No user-loop: the control side must answer without its peer.
        when(api.findPeer(CONTROL_PROCESS_ID)).thenReturn(Optional.empty());
    }

    private void givenPeer() {
        ThinkProcessDocument peer = ThinkProcessDocument.builder()
                .id(PEER_PROCESS_ID)
                .tenantId(TENANT)
                .projectId("proj")
                .sessionId("sess_worker")
                .name("loop")
                .status(ThinkProcessStatus.IDLE)
                .build();
        when(api.findPeer(CONTROL_PROCESS_ID)).thenReturn(Optional.of(peer));
        when(api.snapshotPeerState(peer))
                .thenReturn(new TrillianInternalApi.PeerStateSnapshot(
                        PEER_PROCESS_ID, "loop", ThinkProcessStatus.IDLE, 0L));
    }

    private void givenSession(String userId) {
        when(sessionService.findBySessionId(SESSION))
                .thenReturn(Optional.of(SessionDocument.builder()
                        .sessionId(SESSION)
                        .tenantId(TENANT)
                        .userId(userId)
                        .projectId("proj")
                        .build()));
    }
}
