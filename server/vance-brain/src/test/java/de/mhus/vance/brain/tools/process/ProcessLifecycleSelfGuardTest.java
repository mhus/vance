package de.mhus.vance.brain.tools.process;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.StopInitiatorRegistry;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Regression for the self-stop / self-pause lane deadlock (code-review
 * Phase 2): targeting the current process would enqueue the lifecycle
 * task behind the running turn on the same lane while that turn blocks on
 * {@code .get()} — a permanent lane deadlock. Both tools must reject it
 * before touching the lane scheduler, mirroring {@code ProcessSteerTool}.
 */
class ProcessLifecycleSelfGuardTest {

    private final ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);
    private final LaneScheduler laneScheduler = mock(LaneScheduler.class);

    private static ToolInvocationContext ctxFor(String processId) {
        return new ToolInvocationContext("acme", "proj-1", "sess-1", processId, "u-1");
    }

    private static ThinkProcessDocument processWithId(String id) {
        ThinkProcessDocument doc = ThinkProcessDocument.builder()
                .tenantId("acme")
                .sessionId("sess-1")
                .name(id)
                .build();
        doc.setId(id);
        return doc;
    }

    @Test
    @SuppressWarnings("unchecked")
    void processStop_selfTarget_isRejected_withoutTouchingLane() {
        ObjectProvider<ThinkEngineService> engineProvider = mock(ObjectProvider.class);
        StopInitiatorRegistry stopInitiatorRegistry = mock(StopInitiatorRegistry.class);
        ProcessStopTool tool = new ProcessStopTool(
                thinkProcessService, engineProvider, laneScheduler, stopInitiatorRegistry);
        when(thinkProcessService.findByName("acme", "sess-1", "proc-1"))
                .thenReturn(Optional.of(processWithId("proc-1")));

        assertThatThrownBy(() -> tool.invoke(Map.of("name", "proc-1"), ctxFor("proc-1")))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("self-stop");

        verify(laneScheduler, never()).submit(any(), any(java.util.concurrent.Callable.class));
    }

    @Test
    void processPause_selfTarget_isRejected_withoutTouchingLane() {
        ProcessPauseTool tool = new ProcessPauseTool(thinkProcessService, laneScheduler);
        when(thinkProcessService.findByName("acme", "sess-1", "proc-1"))
                .thenReturn(Optional.of(processWithId("proc-1")));

        assertThatThrownBy(() -> tool.invoke(Map.of("name", "proc-1"), ctxFor("proc-1")))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("self-pause");

        verify(laneScheduler, never()).submit(any(), any(java.util.concurrent.Callable.class));
    }
}
