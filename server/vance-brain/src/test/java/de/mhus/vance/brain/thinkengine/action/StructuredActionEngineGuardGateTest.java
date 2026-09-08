package de.mhus.vance.brain.thinkengine.action;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.guard.ShootyGuardService;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SystemPromptComposer;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.thinkengine.TurnContextHandler;
import de.mhus.vance.brain.thinkengine.TurnContextHandlerRegistry;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the guard hooks of the shared single-action engine base
 * ({@code StructuredActionEngine} — Arthur + Eddie) at the gate level.
 *
 * <p>The yield gate is a deliberate v2 decision
 * ({@code planning/archive/completion-guard.md} phase 3): the completion
 * guard fires only on a turn that <b>appended a chat and is not awaiting
 * the user</b> — Arthur's {@code DELEGATE}/{@code WAIT} yields, where the
 * process would otherwise park IDLE and the guard's {@code continueWith}
 * gives it more work. A plain conversational reply parks
 * awaiting-user-input (BLOCKED); challenging every "hi" with "are you
 * really done?" is exactly what the gate prevents. Frankie has no such
 * gate — its natural stop always yields.
 *
 * <p>Also pins that the turn-start hook delegates to the combined anchor
 * {@link ShootyGuardService#guardsOnTurnStart} (reset + start guards, in
 * that order, owned in one place — the engines must not call the two
 * steps separately).
 */
class StructuredActionEngineGuardGateTest {

    private final ShootyGuardService guardService = mock(ShootyGuardService.class);
    private final TestEngine engine = new TestEngine(guardService);
    private final ThinkProcessDocument process = ThinkProcessDocument.builder()
            .id("p1")
            .tenantId("acme")
            .projectId("proj")
            .sessionId("s1")
            .build();

    /** Minimal concrete engine — every abstract method is a stub; only
     *  the two inherited guard hooks are under test. */
    private static final class TestEngine extends StructuredActionEngine {

        TestEngine(ShootyGuardService guards) {
            super(
                    new StreamingProperties(),
                    mock(LlmCallTracker.class),
                    JsonMapper.builder().build(),
                    mock(SystemPromptComposer.class),
                    guards,
                    mock(ActionLoopJudgeService.class),
                    mock(ThinkProcessService.class),
                    new TurnContextHandlerRegistry(List.<TurnContextHandler>of()),
                    mock(de.mhus.vance.brain.ai.attachment.AttachedUserMessageComposer.class));
        }

        @Override
        public String name() {
            return "test-engine";
        }

        @Override
        public String title() {
            return name();
        }

        @Override
        public String description() {
            return name();
        }

        @Override
        public String version() {
            return "0.0.1";
        }

        @Override
        public void start(ThinkProcessDocument p, ThinkEngineContext ctx) {
            // stub
        }

        @Override
        public void resume(ThinkProcessDocument p, ThinkEngineContext ctx) {
            // stub
        }

        @Override
        public void stop(ThinkProcessDocument p, ThinkEngineContext ctx) {
            // stub
        }

        @Override
        public void suspend(ThinkProcessDocument p, ThinkEngineContext ctx) {
            // stub
        }

        @Override
        public void steer(ThinkProcessDocument p, ThinkEngineContext ctx, SteerMessage message) {
            // stub
        }

        @Override
        protected String actionToolName() {
            return "test_action";
        }

        @Override
        protected String actionToolDescription() {
            return actionToolName();
        }

        @Override
        protected Map<String, Object> actionToolSchema() {
            return Map.of();
        }

        @Override
        protected Set<String> supportedActionTypes() {
            return Set.of();
        }

        @Override
        protected ActionTurnOutcome handleAction(EngineAction action, ThinkProcessDocument p, ThinkEngineContext ctx) {
            return new ActionTurnOutcome(null, true);
        }
    }

    @Test
    void yieldGate_firesWhenChatAppendedAndNotAwaitingUser() {
        // DELEGATE/WAIT-style yield: message emitted, process would park
        // IDLE — the completion guard judges it.
        engine.runCompletionGuard(process, "started the worker", /*appendedChat*/ true, /*awaiting*/ false);

        verify(guardService).evaluate(process, "started the worker", /*naturalStop*/ true);
    }

    @Test
    void yieldGate_skipsConversationalReply_awaitingUserInput() {
        // A chat reply parks the process awaiting the user — challenging
        // it with a completion guard is the no-op the gate exists for.
        engine.runCompletionGuard(process, "hi there!", /*appendedChat*/ true, /*awaiting*/ true);

        verify(guardService, never()).evaluate(any(), any(), anyBoolean());
    }

    @Test
    void yieldGate_skipsTurnWithoutChat() {
        engine.runCompletionGuard(process, null, /*appendedChat*/ false, /*awaiting*/ false);

        verify(guardService, never()).evaluate(any(), any(), anyBoolean());
    }

    @Test
    void yieldGate_failOpenOnEvaluationCrash() {
        when(guardService.evaluate(any(), any(), anyBoolean())).thenThrow(new RuntimeException("boom"));

        // A broken guard must not break the engine turn — the exception
        // is logged and swallowed by the shared hook.
        assertThatCode(() -> engine.runCompletionGuard(process, "done", /*appendedChat*/ true, /*awaiting*/ false))
                .doesNotThrowAnyException();
    }

    @Test
    void turnStartHook_delegatesToCombinedAnchor() {
        List<SteerMessage> inbox =
                List.of(new SteerMessage.UserChatInput(Instant.now(), null, "alice", "refactor the login flow"));

        engine.guardsOnTurnStart(process, inbox);

        // Reset + start guards in one call — the ordering lives in the
        // service anchor, not per engine (shooty.md §2.2).
        verify(guardService).guardsOnTurnStart(process, inbox);
    }
}
