package de.mhus.vance.brain.trillian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The wakeup exists so a Trillian notices what nobody pushed at it. Two
 * things decide whether it is any good: that it stays quiet while there
 * is nothing to notice, and that it fires when the silence itself is the
 * problem.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrillianWakeupServiceTest {

    private static final String LOOP = "loop-1";
    private static final ZoneId ZONE = ZoneId.of("UTC");

    @Mock
    ThinkProcessService thinkProcessService;

    @Test
    void aRunningWorkerSuppressesTheWakeup() {
        // It will emit a terminal event by itself. A timer next to it is
        // a second alarm for the same appointment.
        givenChildren(child(ThinkProcessStatus.RUNNING));

        service().arm(loop(ThinkProcessStatus.IDLE), ZONE);

        verify(thinkProcessService).setEngineParamOverride(
                LOOP, TrillianWakeupService.PARAM_NEXT_WAKEUP_AT, null);
    }

    @Test
    void aParkedWorkerIsExactlyWhyTheWakeupExists() {
        // IDLE means it asked something and is waiting. Nothing will ever
        // arrive on its own — this is the case a heartbeat is for.
        givenChildren(child(ThinkProcessStatus.IDLE));

        service().arm(loop(ThinkProcessStatus.IDLE), ZONE);

        assertThat(armedAt()).isNotNull();
    }

    @Test
    void aQuietLoopIsArmed() {
        givenChildren();

        service().arm(loop(ThinkProcessStatus.IDLE), ZONE);

        assertThat(armedAt()).isNotNull();
    }

    @Test
    void aClosedOrPausedLoopIsNeverArmed() {
        givenChildren();

        service().arm(loop(ThinkProcessStatus.CLOSED), ZONE);
        service().arm(loop(ThinkProcessStatus.PAUSED), ZONE);

        verify(thinkProcessService, never()).setEngineParamOverride(
                anyString(), eq(TrillianWakeupService.PARAM_WAKEUP_STEP), any());
    }

    @Test
    void theCadenceDecelerates() {
        givenChildren();
        TrillianWakeupService service = service();

        assertThat(service.minutesFor(0, ZONE)).isEqualTo(10);
        assertThat(service.minutesFor(1, ZONE)).isEqualTo(20);
        assertThat(service.minutesFor(2, ZONE)).isEqualTo(40);
        assertThat(service.minutesFor(3, ZONE)).isEqualTo(60);
        // Past the end of the ladder it stays at the cap rather than
        // running off the array.
        assertThat(service.minutesFor(99, ZONE)).isEqualTo(60);
    }

    @Test
    void armingAdvancesTheStep() {
        givenChildren();
        ThinkProcessDocument loop = loop(ThinkProcessStatus.IDLE);
        loop.getEngineParamOverrides().put(TrillianWakeupService.PARAM_WAKEUP_STEP, 1);

        service().arm(loop, ZONE);

        verify(thinkProcessService).setEngineParamOverride(
                LOOP, TrillianWakeupService.PARAM_WAKEUP_STEP, 2);
    }

    @Test
    void nightStretchesTheGap() {
        // Nobody is waiting for an answer at 3 a.m.
        assertThat(TrillianWakeupService.isNight(LocalTime.of(3, 0))).isTrue();
        assertThat(TrillianWakeupService.isNight(LocalTime.of(22, 30))).isTrue();
        assertThat(TrillianWakeupService.isNight(LocalTime.of(11, 0))).isFalse();
    }

    @Test
    void dueOnlyOnceTheTimeHasPassed() {
        ThinkProcessDocument loop = loop(ThinkProcessStatus.IDLE);
        Instant now = Instant.parse("2026-08-14T12:00:00Z");
        loop.getEngineParamOverrides().put(
                TrillianWakeupService.PARAM_NEXT_WAKEUP_AT, now.plusSeconds(60).toEpochMilli());

        assertThat(service().isDue(loop, now)).isFalse();
        assertThat(service().isDue(loop, now.plusSeconds(61))).isTrue();
    }

    @Test
    void anUnarmedLoopIsRecognisableAsSuch() {
        // The tick needs this to notice a loop that fell out of the
        // schedule — armed-but-not-due and never-armed look the same to
        // isDue, and only one of them is a problem.
        ThinkProcessDocument loop = loop(ThinkProcessStatus.IDLE);

        assertThat(service().isArmed(loop)).isFalse();

        loop.getEngineParamOverrides().put(
                TrillianWakeupService.PARAM_NEXT_WAKEUP_AT, Instant.now().toEpochMilli());
        assertThat(service().isArmed(loop)).isTrue();
    }

    @Test
    void anUnarmedLoopIsNeverDue() {
        assertThat(service().isDue(loop(ThinkProcessStatus.IDLE), Instant.now())).isFalse();
    }

    // ──── helpers ───────────────────────────────────────────────────────

    private TrillianWakeupService service() {
        return new TrillianWakeupService(thinkProcessService);
    }

    private void givenChildren(ThinkProcessDocument... children) {
        when(thinkProcessService.findByParentProcessId(LOOP)).thenReturn(List.of(children));
    }

    private @org.jspecify.annotations.Nullable Object armedAt() {
        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(thinkProcessService).setEngineParamOverride(
                eq(LOOP), eq(TrillianWakeupService.PARAM_NEXT_WAKEUP_AT), value.capture());
        return value.getValue();
    }

    private static ThinkProcessDocument loop(ThinkProcessStatus status) {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId(LOOP);
        p.setStatus(status);
        p.setEngineParamOverrides(new LinkedHashMap<>(Map.of()));
        return p;
    }

    private static ThinkProcessDocument child(ThinkProcessStatus status) {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId("child-" + status);
        p.setStatus(status);
        return p;
    }
}
