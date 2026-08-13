package de.mhus.vance.brain.wakeup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class WakeupRegistryTest {

    private static final String PROCESS = "p-1";
    private static final String OTHER_PROCESS = "p-2";

    private EngineMessageRouter router;
    private ThinkProcessService thinkProcessService;
    private WakeupRegistry registry;

    @BeforeEach
    void setUp() {
        router = mock(EngineMessageRouter.class);
        thinkProcessService = mock(ThinkProcessService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<EngineMessageRouter> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(router);
        @SuppressWarnings("unchecked")
        ObjectProvider<ThinkProcessService> processProvider = mock(ObjectProvider.class);
        when(processProvider.getObject()).thenReturn(thinkProcessService);
        when(router.dispatch(any(), any(), any())).thenReturn(true);
        givenStatus(ThinkProcessStatus.IDLE);
        registry = new WakeupRegistry(provider, processProvider);
    }

    private void givenStatus(ThinkProcessStatus status) {
        ThinkProcessDocument doc = new ThinkProcessDocument();
        doc.setId(PROCESS);
        doc.setStatus(status);
        when(thinkProcessService.findById(any())).thenReturn(Optional.of(doc));
    }

    @AfterEach
    void tearDown() {
        registry.shutdown();
    }

    @Test
    void schedule_thenFire_dispatchesProcessEventViaRouter() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        when(router.dispatch(eq(PROCESS), eq(PROCESS), any())).thenAnswer(inv -> {
            fired.countDown();
            return true;
        });

        String correlationId = registry.schedule(
                PROCESS, Duration.ofMillis(50), "test-label", Map.of("k", "v"));

        assertThat(fired.await(1, TimeUnit.SECONDS)).isTrue();
        ArgumentCaptor<PendingMessageDocument> docCaptor =
                ArgumentCaptor.forClass(PendingMessageDocument.class);
        verify(router, times(1)).dispatch(eq(PROCESS), eq(PROCESS), docCaptor.capture());
        PendingMessageDocument doc = docCaptor.getValue();
        assertThat(doc.getType()).isEqualTo(PendingMessageType.PROCESS_EVENT);
        assertThat(doc.getEventType()).isEqualTo(ProcessEventType.SCHEDULED_WAKEUP);
        assertThat(doc.getSourceProcessId()).isEqualTo(PROCESS);
        assertThat(doc.getContent()).isEqualTo("test-label");
        assertThat(doc.getPayload()).containsEntry("correlationId", correlationId);
        assertThat(doc.getPayload()).containsEntry("label", "test-label");
        assertThat(doc.getPayload()).containsEntry("payload", Map.of("k", "v"));

        // Once fired, the entry must be gone — list returns empty.
        assertThat(registry.list(PROCESS)).isEmpty();
    }

    @Test
    void pausedProcess_swallowsTheWakeup() throws Exception {
        givenStatus(ThinkProcessStatus.PAUSED);

        registry.schedule(PROCESS, Duration.ofMillis(30), "tick", null);
        Thread.sleep(200);

        // A paused process runs no turns, so delivering would only stack
        // stale ticks in an inbox nobody drains — three days of hourly
        // polling would greet the resume with 72 of them.
        verify(router, never()).dispatch(any(), any(), any());
        assertThat(registry.list(PROCESS)).isEmpty();
    }

    @Test
    void suspendedAndClosed_swallowTheWakeupToo() throws Exception {
        for (ThinkProcessStatus status : java.util.List.of(
                ThinkProcessStatus.SUSPENDED, ThinkProcessStatus.CLOSED)) {
            givenStatus(status);
            registry.schedule(PROCESS, Duration.ofMillis(30), "tick", null);
        }
        Thread.sleep(250);

        verify(router, never()).dispatch(any(), any(), any());
    }

    @Test
    void unknownStatus_stillDelivers() throws Exception {
        // Not knowing the status must not silently eat the wakeup — the
        // safe side here is delivery, since the turn itself is gated
        // again downstream.
        when(thinkProcessService.findById(any())).thenReturn(Optional.empty());
        CountDownLatch fired = new CountDownLatch(1);
        when(router.dispatch(eq(PROCESS), eq(PROCESS), any())).thenAnswer(inv -> {
            fired.countDown();
            return true;
        });

        registry.schedule(PROCESS, Duration.ofMillis(30), "tick", null);

        assertThat(fired.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void cancel_beforeFire_preventsDispatch() throws Exception {
        String correlationId = registry.schedule(
                PROCESS, Duration.ofMillis(500), "to-cancel", null);

        boolean cancelled = registry.cancel(PROCESS, correlationId);

        assertThat(cancelled).isTrue();
        // Give the scheduler a moment — nothing should fire.
        Thread.sleep(700);
        verify(router, never()).dispatch(any(), any(), any());
    }

    @Test
    void cancel_afterFire_returnsFalse() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        when(router.dispatch(any(), any(), any())).thenAnswer(inv -> {
            fired.countDown();
            return true;
        });

        String correlationId = registry.schedule(
                PROCESS, Duration.ofMillis(50), "already-fired", null);
        assertThat(fired.await(1, TimeUnit.SECONDS)).isTrue();
        // After dispatch the entry is removed; a follow-up cancel finds
        // nothing and reports false.
        Thread.sleep(50);

        assertThat(registry.cancel(PROCESS, correlationId)).isFalse();
    }

    @Test
    void cancel_unknownCorrelationId_returnsFalse() {
        assertThat(registry.cancel(PROCESS, "no-such-id")).isFalse();
    }

    @Test
    void cancel_foreignProcess_returnsFalseAndPreservesOriginal() {
        String correlationId = registry.schedule(
                PROCESS, Duration.ofSeconds(10), "long", null);

        // Some other process tries to cancel — must not succeed.
        assertThat(registry.cancel(OTHER_PROCESS, correlationId)).isFalse();
        assertThat(registry.list(PROCESS))
                .extracting(WakeupHandle::correlationId)
                .containsExactly(correlationId);
    }

    @Test
    void cancelAll_revokesEveryWakeupForProcess() throws Exception {
        registry.schedule(PROCESS, Duration.ofMillis(500), "a", null);
        registry.schedule(PROCESS, Duration.ofMillis(600), "b", null);
        registry.schedule(OTHER_PROCESS, Duration.ofMillis(500), "c", null);

        registry.cancelAll(PROCESS);

        Thread.sleep(800);
        // Only OTHER_PROCESS's wakeup is allowed to fire.
        verify(router, times(1)).dispatch(eq(OTHER_PROCESS), eq(OTHER_PROCESS), any());
        verify(router, never()).dispatch(eq(PROCESS), eq(PROCESS), any());
    }

    @Test
    void list_returnsActiveWakeups() {
        String a = registry.schedule(PROCESS, Duration.ofSeconds(10), "first", null);
        String b = registry.schedule(PROCESS, Duration.ofSeconds(10), "second", null);

        List<WakeupHandle> handles = registry.list(PROCESS);

        assertThat(handles)
                .extracting(WakeupHandle::correlationId)
                .containsExactlyInAnyOrder(a, b);
        assertThat(handles)
                .extracting(WakeupHandle::label)
                .containsExactlyInAnyOrder("first", "second");
    }

    @Test
    void list_emptyForUnknownProcess() {
        assertThat(registry.list("nobody-here")).isEmpty();
    }

    @Test
    void schedule_blankProcessId_throws() {
        assertThatThrownBy(() ->
                registry.schedule("", Duration.ofSeconds(1), "x", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void schedule_nonPositiveDelay_throws() {
        assertThatThrownBy(() ->
                registry.schedule(PROCESS, Duration.ZERO, "x", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                registry.schedule(PROCESS, Duration.ofSeconds(-1), "x", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fire_dispatchReturnsFalse_doesNotPropagate() throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        when(router.dispatch(any(), any(), any())).thenAnswer(inv -> {
            attempted.countDown();
            return false;
        });

        registry.schedule(PROCESS, Duration.ofMillis(50), "drop-me", null);

        assertThat(attempted.await(1, TimeUnit.SECONDS)).isTrue();
        // Entry still removed even on dispatch-failure — the timer
        // fired exactly once; retry is not the registry's concern.
        Thread.sleep(50);
        assertThat(registry.list(PROCESS)).isEmpty();
    }
}
