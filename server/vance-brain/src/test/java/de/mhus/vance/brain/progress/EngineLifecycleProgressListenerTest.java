package de.mhus.vance.brain.progress;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessStatusChangedEvent;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The terminal-cleanup hook: on a process's transition to CLOSED the listener
 * must drop its LLM counters, so {@code LlmCallTracker.byProcess} doesn't grow
 * for the pod's lifetime (forget() previously had no caller).
 */
class EngineLifecycleProgressListenerTest {

    private ThinkProcessService thinkProcessService;
    private ProgressEmitter progressEmitter;
    private LlmCallTracker llmCallTracker;
    private EngineLifecycleProgressListener listener;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        progressEmitter = mock(ProgressEmitter.class);
        llmCallTracker = mock(LlmCallTracker.class);
        listener = new EngineLifecycleProgressListener(
                thinkProcessService, progressEmitter, llmCallTracker);
    }

    @Test
    void closedTransition_forgetsLlmCounters() {
        when(thinkProcessService.findById("p-1")).thenReturn(Optional.empty());

        listener.onStatusChanged(event(ThinkProcessStatus.RUNNING, ThinkProcessStatus.CLOSED));

        verify(llmCallTracker).forget("p-1");
    }

    @Test
    void nonClosedTransition_doesNotForget() {
        when(thinkProcessService.findById("p-1"))
                .thenReturn(Optional.of(ThinkProcessDocument.builder()
                        .id("p-1").name("worker").build()));

        listener.onStatusChanged(event(ThinkProcessStatus.RUNNING, ThinkProcessStatus.PAUSED));

        verify(llmCallTracker, never()).forget(any());
    }

    @Test
    void noOpTransition_doesNotForget() {
        listener.onStatusChanged(event(ThinkProcessStatus.CLOSED, ThinkProcessStatus.CLOSED));

        verify(llmCallTracker, never()).forget(any());
    }

    private static ThinkProcessStatusChangedEvent event(
            ThinkProcessStatus prior, ThinkProcessStatus next) {
        return new ThinkProcessStatusChangedEvent("p-1", "t", "s", null, prior, next);
    }
}
