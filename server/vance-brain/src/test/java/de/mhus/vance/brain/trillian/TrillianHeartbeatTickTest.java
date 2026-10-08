package de.mhus.vance.brain.trillian;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.trillian.nature.SelfCheckFinding;
import de.mhus.vance.brain.trillian.nature.TrillianNature;
import de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry;
import de.mhus.vance.shared.megadodo.MegadodoService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The tick is where the four states of a self-check are wired together,
 * and the cheap one is the one worth pinning: a due loop whose Nature
 * sees nothing must re-arm <em>without</em> running a turn. That property
 * — "one query, no tokens" — is the entire reason an hourly rhythm is
 * affordable, and nothing else in the code says it out loud.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrillianHeartbeatTickTest {

    private static final String LOOP = "loop-1";

    @Mock
    ClusterService clusterService;

    @Mock
    ThinkProcessService thinkProcessService;

    @Mock
    TrillianWakeupService wakeupService;

    @Mock
    ProcessEventEmitter eventEmitter;

    @Mock
    TrillianNatureRegistry natureRegistry;

    @Mock
    TrillianNature nature;

    @Mock
    MegadodoService megadodoService;

    @Mock
    TrillianWakeupClaimService wakeupClaimService;

    @Mock
    TrillianAgendaService agendaService;

    TrillianHeartbeatTick tick;

    @BeforeEach
    void setUp() {
        when(natureRegistry.resolve(any())).thenReturn(nature);
        when(wakeupClaimService.claim(any(), any(), any())).thenReturn(true);
        when(wakeupService.nextWakeupAt(any())).thenReturn(java.time.Instant.now());
        when(wakeupService.hasScheduleMarker(any())).thenReturn(true);
        when(agendaService.findings(any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(List.of());
        tick = new TrillianHeartbeatTick(
                clusterService,
                thinkProcessService,
                wakeupService,
                wakeupClaimService,
                agendaService,
                eventEmitter,
                natureRegistry,
                megadodoService);
    }

    @Test
    void anIdleLoopWithoutAnAppointment_isAdoptedIntoTheSchedule() {
        // Arming happens at the loop's yield point, and a loop that fell
        // out of the schedule will not yield again until something wakes
        // it — which is the thing that is missing. Nobody else can put it
        // back in.
        givenLoop(ThinkProcessStatus.IDLE);
        when(wakeupService.isArmed(any())).thenReturn(false);

        tick.tick();

        verify(wakeupService).arm(any(), any());
        verify(nature, never()).selfCheckFindings(any());
        verify(eventEmitter, never()).scheduleTurn(anyString());
    }

    @Test
    void aDueLoopWithNothingToLookAt_reArmsWithoutSpendingATurn() {
        givenDueLoop();
        when(nature.selfCheckFindings(any())).thenReturn(List.of());

        tick.tick();

        verify(wakeupService).arm(any(), any());
        verify(thinkProcessService, never()).appendPending(anyString(), any());
        verify(eventEmitter, never()).scheduleTurn(anyString());
        verify(nature, never()).selfCheckDelivered(any(), any());
    }

    @Test
    void aDueLoopWithFindings_isWokenAndTheNatureIsToldItWasDelivered() {
        givenDueLoop();
        List<SelfCheckFinding> findings = List.of(finding());
        when(nature.selfCheckFindings(any())).thenReturn(findings);
        when(thinkProcessService.appendPending(eq(LOOP), any())).thenReturn(true);

        tick.tick();

        verify(wakeupService).disarm(any());
        verify(eventEmitter).scheduleTurn(LOOP);
        verify(nature).selfCheckDelivered(any(), eq(findings));
    }

    @Test
    void aWakeupThatDidNotLand_spendsNothing() {
        // The Nature's bookkeeping — the probe budget, the blocked round,
        // the close of a looping worker — is about findings that were
        // reported. A queue append that failed reported nothing.
        givenDueLoop();
        when(nature.selfCheckFindings(any())).thenReturn(List.of(finding()));
        when(thinkProcessService.appendPending(eq(LOOP), any())).thenReturn(false);

        tick.tick();

        verify(eventEmitter, never()).scheduleTurn(anyString());
        verify(nature, never()).selfCheckDelivered(any(), any());
        // Nothing woke, so nothing is worth a feed row either.
        verify(megadodoService, never())
                .trillianWokeUp(anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void aWakeup_landsInTheFeedWithItsReasons() {
        // Why a Trillian started working at four in the morning is only
        // answerable afterwards if the reason was written down: the ladder
        // leaves no trace, and the self-check command is consumed by the
        // turn it triggers.
        givenDueLoop();
        when(nature.selfCheckFindings(any())).thenReturn(List.of(finding()));
        when(thinkProcessService.appendPending(eq(LOOP), any())).thenReturn(true);

        tick.tick();

        verify(megadodoService)
                .trillianWokeUp(
                        eq("acme"),
                        eq("proj"),
                        eq(LOOP),
                        eq("_trillian-void-1535"),
                        anyString(),
                        eq(List.of("[worker_waiting] ask-worker: parked since 20 minutes")));
    }

    @Test
    void aQuietRound_writesNoFeedRow() {
        // The common round, hourly, per loop, forever. A row for it would
        // bury the ones that mean something.
        givenDueLoop();
        when(nature.selfCheckFindings(any())).thenReturn(List.of());

        tick.tick();

        verify(megadodoService, never())
                .trillianWokeUp(anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void aLoopThatIsNotIdle_isLeftAlone() {
        // It is working, or paused, or gone. Either way the clock measures
        // silence and there is none.
        givenLoop(ThinkProcessStatus.RUNNING);

        tick.tick();

        verify(wakeupService, never()).arm(any(), any());
        verify(nature, never()).selfCheckFindings(any());
        verify(eventEmitter, never()).scheduleTurn(anyString());
    }

    @Test
    void aDueSchedule_wakesTheLoopEvenWhenTheLadderIsNotDue() {
        // A 09:00 appointment must not wait for the loop's next look
        // around — schedules fire on the scan grid (plan §4.4).
        givenLoop(ThinkProcessStatus.IDLE);
        when(wakeupService.isArmed(any())).thenReturn(true);
        when(wakeupService.isDue(any(), any())).thenReturn(false);
        when(wakeupService.isScheduleDue(any(), any())).thenReturn(true);
        when(wakeupService.scheduleMarker(any())).thenReturn(java.time.Instant.ofEpochMilli(1000));
        List<SelfCheckFinding> due = List.of(
                new SelfCheckFinding(SelfCheckFinding.Kind.SCHEDULE_DUE, "standup", "standup", "daily stand-up"));
        when(agendaService.scheduleFindings(any())).thenReturn(due);
        when(thinkProcessService.appendPending(eq(LOOP), any())).thenReturn(true);

        tick.tick();

        verify(eventEmitter).scheduleTurn(LOOP);
        verify(wakeupClaimService).claim("acme", LOOP, "s1000");
        verify(agendaService).delivered(any(), eq(due));
        // Only the appointment: the Nature's look-around belongs to the ladder.
        verify(nature, never()).selfCheckFindings(any());
    }

    @Test
    void aDueSchedule_fires_whileTheLadderIsDisarmedByARunningWorker() {
        // A running worker keeps the ladder off; appointments do not care.
        givenLoop(ThinkProcessStatus.IDLE);
        when(wakeupService.isArmed(any())).thenReturn(false);
        when(wakeupService.isScheduleDue(any(), any())).thenReturn(true);
        when(wakeupService.scheduleMarker(any())).thenReturn(java.time.Instant.ofEpochMilli(2000));
        when(agendaService.scheduleFindings(any()))
                .thenReturn(List.of(new SelfCheckFinding(
                        SelfCheckFinding.Kind.SCHEDULE_DUE, "standup", "standup", "daily stand-up")));
        when(thinkProcessService.appendPending(eq(LOOP), any())).thenReturn(true);

        tick.tick();

        verify(eventEmitter).scheduleTurn(LOOP);
    }

    @Test
    void aLoopWithoutScheduleMarker_getsItComputed() {
        givenLoop(ThinkProcessStatus.IDLE);
        when(wakeupService.hasScheduleMarker(any())).thenReturn(false);
        when(wakeupService.isArmed(any())).thenReturn(true);

        tick.tick();

        verify(agendaService).refreshScheduleMarker(any(ThinkProcessDocument.class));
    }

    @Test
    void aLostClaim_wakesNothing() {
        // Another pod won this slot and fires; this one must not.
        givenDueLoop();
        when(nature.selfCheckFindings(any())).thenReturn(List.of(finding()));
        when(wakeupClaimService.claim(any(), any(), any())).thenReturn(false);

        tick.tick();

        verify(thinkProcessService, never()).appendPending(anyString(), any());
        verify(eventEmitter, never()).scheduleTurn(anyString());
    }

    private void givenDueLoop() {
        givenLoop(ThinkProcessStatus.IDLE);
        when(wakeupService.isArmed(any())).thenReturn(true);
        when(wakeupService.isDue(any(), any())).thenReturn(true);
    }

    private void givenLoop(ThinkProcessStatus status) {
        ThinkProcessDocument loop = new ThinkProcessDocument();
        loop.setId(LOOP);
        loop.setTenantId("acme");
        loop.setProjectId("proj");
        loop.setStatus(status);
        loop.getEngineParams().put(TrillianSessionBootstrapper.PARAM_TRILLIAN_USER_NAME, "_trillian-void-1535");
        when(wakeupService.liveLoops(org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(loop));
    }

    private static SelfCheckFinding finding() {
        return new SelfCheckFinding(
                SelfCheckFinding.Kind.WORKER_WAITING, "ask-worker", "child-1", "parked since 20 minutes");
    }
}
