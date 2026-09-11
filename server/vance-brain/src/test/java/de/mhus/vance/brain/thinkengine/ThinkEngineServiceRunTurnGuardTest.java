package de.mhus.vance.brain.thinkengine;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.ai.attachment.ToolImageHarvester;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.history.HistoryTagBuilder;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.brain.progress.ProgressToolListener;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.brain.tools.ToolInvocationListener;
import de.mhus.vance.brain.tools.ToolResultStorage;
import de.mhus.vance.brain.tools.budget.ToolBudgetService;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.llmtrace.LlmTraceService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.toolhealth.ToolHealthService;
import de.mhus.vance.shared.toolusage.ToolUsageService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Tests for the exception guard in {@link ThinkEngineService#runTurn}: a
 * delegated worker whose turn dies mid-flight must be closed
 * {@code INCOMPLETE} so {@link ParentNotificationListener} emits a
 * FAILED ProcessEvent on the parent — otherwise the delegation pointer
 * stays armed forever (the FootToolPackLlmTest incident, 2026-09-11).
 * Top-level processes and already-closed workers are left alone.
 */
class ThinkEngineServiceRunTurnGuardTest {

    private static final String ENGINE_NAME = "throwing";
    private static final String WORKER_ID = "worker-1";
    private static final String PARENT_ID = "parent-1";

    private ThinkProcessService thinkProcessService;
    private ThinkEngineService service;

    /** Minimal engine that explodes the moment its turn runs. */
    private final ThinkEngine boom = new ThinkEngine() {
        @Override
        public String name() {
            return ENGINE_NAME;
        }

        @Override
        public String title() {
            return ENGINE_NAME;
        }

        @Override
        public String description() {
            return "test engine";
        }

        @Override
        public String version() {
            return "test";
        }

        @Override
        public void start(ThinkProcessDocument process, ThinkEngineContext context) {}

        @Override
        public void resume(ThinkProcessDocument process, ThinkEngineContext context) {}

        @Override
        public void suspend(ThinkProcessDocument process, ThinkEngineContext context) {}

        @Override
        public void stop(ThinkProcessDocument process, ThinkEngineContext context) {}

        @Override
        public void steer(ThinkProcessDocument process, ThinkEngineContext context, SteerMessage message) {}

        @Override
        public void runTurn(ThinkProcessDocument process, ThinkEngineContext context) {
            throw new IllegalStateException("simulated mid-turn failure");
        }
    };

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        SessionService sessionService = mock(SessionService.class);
        thinkProcessService = mock(ThinkProcessService.class);
        when(sessionService.findBySessionId(anyString()))
                .thenReturn(Optional.of(SessionDocument.builder()
                        .sessionId("sess_test")
                        .tenantId("acme")
                        .projectId("proj")
                        .build()));
        ObjectProvider<RecipeResolver> recipeResolverProvider = mock(ObjectProvider.class);
        when(recipeResolverProvider.getIfAvailable()).thenReturn(null);
        ProgressToolListener progressToolListener = mock(ProgressToolListener.class);
        when(progressToolListener.forProcess(any())).thenReturn(mock(ToolInvocationListener.class));

        service = new ThinkEngineService(
                List.of(boom),
                mock(AiModelService.class),
                mock(SettingService.class),
                mock(ChatMessageService.class),
                mock(ToolDispatcher.class),
                mock(ClientEventPublisher.class),
                sessionService,
                thinkProcessService,
                mock(ProcessEventEmitter.class),
                mock(ProgressEmitter.class),
                progressToolListener,
                mock(LlmTraceService.class),
                mock(HistoryTagBuilder.class),
                mock(ToolResultStorage.class),
                mock(ToolHealthService.class),
                mock(ToolImageHarvester.class),
                mock(ToolBudgetService.class),
                mock(ToolUsageService.class),
                recipeResolverProvider);
    }

    @Test
    void workerTurnThrows_closesIncompleteAndRethrows() {
        ThinkProcessDocument worker = worker(ThinkProcessStatus.RUNNING);

        assertThatThrownBy(() -> service.runTurn(worker))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated mid-turn failure");

        // The whole point: the parent must learn. CLOSED + INCOMPLETE
        // maps to a FAILED ProcessEvent on the parent's inbox.
        verify(thinkProcessService).closeProcess(WORKER_ID, CloseReason.INCOMPLETE);
    }

    @Test
    void topLevelTurnThrows_neverCloses() {
        ThinkProcessDocument chat = worker(ThinkProcessStatus.RUNNING);
        // no parent → top-level chat process, not a delegated worker
        ThinkProcessDocument parentless = ThinkProcessDocument.builder()
                .id(chat.getId())
                .tenantId(chat.getTenantId())
                .sessionId(chat.getSessionId())
                .thinkEngine(ENGINE_NAME)
                .status(ThinkProcessStatus.RUNNING)
                .build();

        assertThatThrownBy(() -> service.runTurn(parentless)).isInstanceOf(IllegalStateException.class);

        verify(thinkProcessService, never()).closeProcess(any(), any());
    }

    @Test
    void alreadyClosedWorker_noDoubleClose() {
        ThinkProcessDocument closedWorker = worker(ThinkProcessStatus.CLOSED);

        assertThatThrownBy(() -> service.runTurn(closedWorker)).isInstanceOf(IllegalStateException.class);

        // Engine-side close (graceful maxIter recovery) already landed —
        // closeProcess is atomic on the Mongo side anyway; the guard's
        // pre-check keeps the log honest.
        verify(thinkProcessService, never()).closeProcess(any(), any());
    }

    private static ThinkProcessDocument worker(ThinkProcessStatus status) {
        return ThinkProcessDocument.builder()
                .id(WORKER_ID)
                .tenantId("acme")
                .sessionId("sess_test")
                .thinkEngine(ENGINE_NAME)
                .parentProcessId(PARENT_ID)
                .status(status)
                .build();
    }
}
