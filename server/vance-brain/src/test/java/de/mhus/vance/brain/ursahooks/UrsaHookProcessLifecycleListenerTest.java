package de.mhus.vance.brain.ursahooks;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.action.TriggerKind;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessStatusChangedEvent;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Cycle guard for the self-triggering hook chain (code-review Phase 2): a
 * hook-spawned process (triggerSource=HOOK) must NOT re-fire
 * process-lifecycle hooks on termination, or a process.completed hook with
 * a recipe action would spawn forever. Normal processes fire as before.
 */
class UrsaHookProcessLifecycleListenerTest {

    private ApplicationEventPublisher publisher;
    private ThinkProcessService thinkProcessService;
    private UrsaHookProcessLifecycleListener listener;

    @BeforeEach
    void setUp() {
        publisher = mock(ApplicationEventPublisher.class);
        thinkProcessService = mock(ThinkProcessService.class);
        listener = new UrsaHookProcessLifecycleListener(publisher, thinkProcessService);
    }

    private ThinkProcessDocument closed(String id, CloseReason reason, String triggerSource) {
        ThinkProcessDocument doc = ThinkProcessDocument.builder()
                .tenantId("acme").sessionId("s-1").name(id)
                .status(ThinkProcessStatus.CLOSED)
                .closeReason(reason)
                .triggerSource(triggerSource)
                .build();
        doc.setId(id);
        return doc;
    }

    private ThinkProcessStatusChangedEvent closedEvent(String id) {
        return new ThinkProcessStatusChangedEvent(
                id, "acme", "s-1", null,
                ThinkProcessStatus.RUNNING, ThinkProcessStatus.CLOSED);
    }

    @Test
    void hookSpawnedProcess_doesNotRefireLifecycleHooks() {
        when(thinkProcessService.findById("p-hook"))
                .thenReturn(Optional.of(closed("p-hook", CloseReason.DONE, TriggerKind.HOOK.name())));

        listener.onStatusChanged(closedEvent("p-hook"));

        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void normalProcess_firesLifecycleHook() {
        when(thinkProcessService.findById("p-normal"))
                .thenReturn(Optional.of(closed("p-normal", CloseReason.DONE, null)));

        listener.onStatusChanged(closedEvent("p-normal"));

        verify(publisher).publishEvent(any(UrsaHookFireableEvent.class));
    }
}
