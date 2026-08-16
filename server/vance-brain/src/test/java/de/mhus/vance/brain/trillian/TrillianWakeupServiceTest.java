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
    /** Safely outside the night window, whatever the machine clock says. */
    private static final LocalTime DAY = LocalTime.of(12, 0);

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
        // Fixed midday, because at night the cap would answer for the
        // ladder and this test would pass without testing anything.
        TrillianWakeupService service = service();

        assertThat(service.minutesFor(0, DAY)).isEqualTo(10);
        assertThat(service.minutesFor(1, DAY)).isEqualTo(20);
        assertThat(service.minutesFor(2, DAY)).isEqualTo(40);
        assertThat(service.minutesFor(3, DAY)).isEqualTo(60);
        // Past the end of the ladder it stays at the cap rather than
        // running off the array.
        assertThat(service.minutesFor(99, DAY)).isEqualTo(60);
    }

    @Test
    void theNightCapOutranksTheLadder() {
        // Two hours from the very first step: the point of the cap is
        // that a fresh quiet round at 3 a.m. is still quiet until
        // morning, not that the ladder climbs faster in the dark.
        TrillianWakeupService service = service();

        assertThat(service.minutesFor(0, LocalTime.of(23, 0))).isEqualTo(120);
        assertThat(service.minutesFor(0, LocalTime.of(3, 0))).isEqualTo(120);
        assertThat(service.minutesFor(3, LocalTime.of(3, 0))).isEqualTo(120);
        assertThat(service.minutesFor(99, LocalTime.of(3, 0))).isEqualTo(120);
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
    void theGapIsJittered_butStaysRecognisable() {
        // Trillians armed in the same second would otherwise wake in the
        // same second for good — the ladder is deterministic — and a
        // human with three of them would get three nudges at once.
        TrillianWakeupService service = service();
        java.time.Duration nominal = java.time.Duration.ofMinutes(20);

        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int i = 0; i < 200; i++) {
            java.time.Duration gap = service.jittered(nominal);
            seen.add(gap.toMillis());
            // "about twenty minutes" has to survive the jitter, or the
            // cadence stops meaning anything.
            assertThat(gap).isBetween(
                    java.time.Duration.ofMinutes(16), java.time.Duration.ofMinutes(24));
        }
        assertThat(seen).hasSizeGreaterThan(50);
    }

    @Test
    void aJitteredGapIsNeverInstant() {
        // A small interval and an unlucky draw must not produce a wakeup
        // that is already due when it is armed.
        TrillianWakeupService service = service();

        for (int i = 0; i < 50; i++) {
            assertThat(service.jittered(java.time.Duration.ofSeconds(5)))
                    .isGreaterThanOrEqualTo(java.time.Duration.ofMinutes(1));
        }
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
