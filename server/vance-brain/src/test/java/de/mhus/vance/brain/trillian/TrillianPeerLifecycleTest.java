package de.mhus.vance.brain.trillian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.enginemessage.EngineMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

/**
 * Pause/resume of the Trillian-User peer — the two operations behind
 * {@code user_stop}/{@code user_continue} and {@code //trillian stop}/{@code
 * continue}.
 *
 * <p>The interesting half is the wake-up, not the status write. A worker sitting
 * IDLE with a queued task request is exactly what a human reaches for
 * {@code continue} to fix: there is nothing to un-pause, but a lane-turn is
 * precisely what is missing. A resume that skips the wake for anything but
 * PAUSED is therefore a silent no-op in the case it exists for.
 */
class TrillianPeerLifecycleTest {

    private static final String PEER_ID = "peer-1";

    private ThinkProcessService thinkProcessService;
    private ProcessEventEmitter eventEmitter;
    private LaneScheduler laneScheduler;
    private TrillianInternalApi api;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        eventEmitter = mock(ProcessEventEmitter.class);
        laneScheduler = mock(LaneScheduler.class);
        // Run lane work inline so the .get() inside setPeerStatus completes.
        // anyString() + Callable disambiguates the Runnable overload.
        when(laneScheduler.submit(anyString(), ArgumentMatchers.<Callable<Object>>any()))
                .thenAnswer(inv -> {
                    Callable<?> task = inv.getArgument(1);
                    return CompletableFuture.completedFuture(task.call());
                });
        api = new TrillianInternalApi(
                thinkProcessService,
                mock(EngineMessageRouter.class),
                mock(EngineMessageService.class),
                eventEmitter,
                mock(ChatMessageService.class),
                laneScheduler,
                new de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry(
                        java.util.List.of(
                                new de.mhus.vance.brain.trillian.nature.TrillianNature0(
                                        thinkProcessService))));
    }

    private static ThinkProcessDocument peer(ThinkProcessStatus status) {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId(PEER_ID);
        p.setName("trillian-user");
        p.setStatus(status);
        return p;
    }

    @Test
    void resumePeer_pausedPeer_flipsToIdleAndWakesTheLane() {
        ThinkProcessStatus now = api.resumePeer(peer(ThinkProcessStatus.PAUSED));

        assertThat(now).isEqualTo(ThinkProcessStatus.IDLE);
        verify(thinkProcessService).updateStatus(PEER_ID, ThinkProcessStatus.IDLE);
        verify(eventEmitter).scheduleTurn(PEER_ID);
    }

    @Test
    void resumePeer_idlePeer_wakesTheLaneWithoutRewritingTheStatus() {
        // The queued-inbox case: nothing to un-pause, but the turn has to be
        // scheduled or `continue` does nothing at all.
        ThinkProcessStatus now = api.resumePeer(peer(ThinkProcessStatus.IDLE));

        assertThat(now).isEqualTo(ThinkProcessStatus.IDLE);
        verify(eventEmitter).scheduleTurn(PEER_ID);
        verify(thinkProcessService, never()).updateStatus(eq(PEER_ID), any());
    }

    @Test
    void resumePeer_blockedPeer_flipsToIdleAndWakes() {
        ThinkProcessStatus now = api.resumePeer(peer(ThinkProcessStatus.BLOCKED));

        assertThat(now).isEqualTo(ThinkProcessStatus.IDLE);
        verify(thinkProcessService).updateStatus(PEER_ID, ThinkProcessStatus.IDLE);
        verify(eventEmitter).scheduleTurn(PEER_ID);
    }

    @Test
    void resumePeer_runningPeer_isLeftAloneEntirely() {
        // Already turning — a wake would be noise and an IDLE write would knock
        // it back behind its own turn.
        ThinkProcessStatus now = api.resumePeer(peer(ThinkProcessStatus.RUNNING));

        assertThat(now).isEqualTo(ThinkProcessStatus.RUNNING);
        verify(eventEmitter, never()).scheduleTurn(any());
        verify(thinkProcessService, never()).updateStatus(any(), any());
    }

    @Test
    void resumePeer_closedPeer_isLeftAloneEntirely() {
        ThinkProcessStatus now = api.resumePeer(peer(ThinkProcessStatus.CLOSED));

        assertThat(now).isEqualTo(ThinkProcessStatus.CLOSED);
        verify(eventEmitter, never()).scheduleTurn(any());
        verify(thinkProcessService, never()).updateStatus(any(), any());
    }

    @Test
    void pausePeer_runningPeer_flipsToPausedWithoutWaking() {
        ThinkProcessStatus now = api.pausePeer(peer(ThinkProcessStatus.RUNNING));

        assertThat(now).isEqualTo(ThinkProcessStatus.PAUSED);
        verify(thinkProcessService).updateStatus(PEER_ID, ThinkProcessStatus.PAUSED);
        verify(eventEmitter, never()).scheduleTurn(any());
    }

    @Test
    void pausePeer_alreadyPaused_isANoOp() {
        ThinkProcessStatus now = api.pausePeer(peer(ThinkProcessStatus.PAUSED));

        assertThat(now).isEqualTo(ThinkProcessStatus.PAUSED);
        verify(thinkProcessService, never()).updateStatus(any(), any());
    }
}
