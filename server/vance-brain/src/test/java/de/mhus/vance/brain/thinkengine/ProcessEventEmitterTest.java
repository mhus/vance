package de.mhus.vance.brain.thinkengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.progress.StatusTag;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Failure-path contract of {@link ProcessEventEmitter#runTurnNow}.
 *
 * <p>A turn that blows up must not disappear: it propagates to the
 * caller (the steer handler turns it into a WS error frame, the lane
 * logs it) and the {@code ENGINE_TURN_END} progress ping still fires.
 * Swallowing here left the client waiting forever for an ack that a
 * dead turn never sent.
 */
@ExtendWith(MockitoExtension.class)
class ProcessEventEmitterTest {

    @Mock private ThinkProcessService thinkProcessService;
    @Mock private LaneScheduler laneScheduler;
    @Mock private ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;
    @Mock private ThinkEngineService thinkEngineService;
    @Mock private ProgressEmitter progressEmitter;
    @Mock private ObjectProvider<EngineMessageRouter> messageRouterProvider;

    private ProcessEventEmitter emitter;
    private ThinkProcessDocument process;

    @BeforeEach
    void setUp() {
        emitter = new ProcessEventEmitter(
                thinkProcessService, laneScheduler, thinkEngineServiceProvider,
                progressEmitter, messageRouterProvider);
        process = ThinkProcessDocument.builder()
                .id("proc-1")
                .name("chat")
                .status(ThinkProcessStatus.IDLE)
                .build();
    }

    @Test
    void runTurnNow_engineThrowsError_propagatesToCaller() {
        when(thinkProcessService.findById("proc-1")).thenReturn(Optional.of(process));
        when(thinkEngineServiceProvider.getObject()).thenReturn(thinkEngineService);
        doThrow(new NoClassDefFoundError("com/example/Gone"))
                .when(thinkEngineService).runTurn(process);

        assertThatThrownBy(() -> emitter.runTurnNow("proc-1"))
                .isInstanceOf(NoClassDefFoundError.class);
    }

    @Test
    void runTurnNow_engineThrowsError_stillEmitsTurnEnd() {
        when(thinkProcessService.findById("proc-1")).thenReturn(Optional.of(process));
        when(thinkEngineServiceProvider.getObject()).thenReturn(thinkEngineService);
        doThrow(new IllegalStateException("boom"))
                .when(thinkEngineService).runTurn(process);

        assertThatThrownBy(() -> emitter.runTurnNow("proc-1"))
                .isInstanceOf(IllegalStateException.class);

        verify(progressEmitter).emitStatus(
                eq(process), eq(StatusTag.ENGINE_TURN_END), any());
    }

    @Test
    void runTurnNow_suspendedProcess_skipsTurnEntirely() {
        ThinkProcessDocument suspended = ThinkProcessDocument.builder()
                .id("proc-2")
                .name("chat")
                .status(ThinkProcessStatus.SUSPENDED)
                .build();
        when(thinkProcessService.findById("proc-2")).thenReturn(Optional.of(suspended));

        emitter.runTurnNow("proc-2");

        verify(thinkEngineServiceProvider, never()).getObject();
        verify(progressEmitter, never()).emitStatus(any(), any(StatusTag.class), any());
    }
}
