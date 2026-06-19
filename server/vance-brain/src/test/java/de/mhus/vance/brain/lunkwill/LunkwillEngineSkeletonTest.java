package de.mhus.vance.brain.lunkwill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Skeleton-level tests for {@link LunkwillEngine}. Verifies metadata,
 * lifecycle status writes, and that {@code runTurn} drains the
 * inbox. Does <b>not</b> exercise the LLM loop — that lands in the
 * next change.
 */
class LunkwillEngineSkeletonTest {

    private static final String PROC_ID = "proc-lunkwill-1";

    private ThinkProcessService thinkProcessService;
    private LunkwillEngine engine;
    private ThinkProcessDocument process;
    private ThinkEngineContext ctx;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        engine = new LunkwillEngine(thinkProcessService, new LunkwillProperties());

        process = new ThinkProcessDocument();
        process.setId(PROC_ID);
        process.setTenantId("tenant-x");
        process.setSessionId("session-y");

        ctx = mock(ThinkEngineContext.class);
        when(ctx.drainPending()).thenReturn(List.of());
    }

    @Test
    void metadata_returnsExpectedValues() {
        assertThat(engine.name()).isEqualTo("lunkwill");
        assertThat(engine.title()).contains("Lunkwill");
        assertThat(engine.version()).isEqualTo("0.1.0");
        assertThat(engine.description()).isNotBlank();
    }

    @Test
    void start_transitionsProcessToIdle() {
        engine.start(process, ctx);
        verify(thinkProcessService).updateStatus(PROC_ID, ThinkProcessStatus.IDLE);
    }

    @Test
    void resume_transitionsProcessToIdle() {
        engine.resume(process, ctx);
        verify(thinkProcessService).updateStatus(PROC_ID, ThinkProcessStatus.IDLE);
    }

    @Test
    void suspend_transitionsProcessToSuspended() {
        engine.suspend(process, ctx);
        verify(thinkProcessService).updateStatus(PROC_ID, ThinkProcessStatus.SUSPENDED);
    }

    @Test
    void stop_closesProcessWithStoppedReason() {
        engine.stop(process, ctx);
        verify(thinkProcessService).closeProcess(PROC_ID, CloseReason.STOPPED);
    }

    @Test
    void runTurn_drainsInboxAndReturnsToIdle() {
        engine.runTurn(process, ctx);

        verify(ctx).drainPending();
        verify(thinkProcessService).updateStatus(PROC_ID, ThinkProcessStatus.RUNNING);
        verify(thinkProcessService).updateStatus(PROC_ID, ThinkProcessStatus.IDLE);
    }

    @Test
    void terminationConventionKey_isStable() {
        assertThat(LunkwillTermination.RESULT_TERMINATE_KEY).isEqualTo("_terminate");
    }
}
