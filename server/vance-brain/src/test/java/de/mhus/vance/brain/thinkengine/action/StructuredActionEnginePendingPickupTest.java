package de.mhus.vance.brain.thinkengine.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.AiChat;
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
import dev.langchain4j.data.message.AiMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mid-turn pickup of queued messages at the action-loop boundary
 * ("active message queue", {@code planning/active-message-queue.md} §4 P1).
 *
 * <p>The contract under test: when the engine opted in and a message is
 * queued while the turn is already running, the loop yields at the next
 * boundary — after at least one full LLM round, never before one, never
 * mid tool-chain — and carries the in-turn narration out so the next turn
 * keeps the record of what happened.
 */
class StructuredActionEnginePendingPickupTest {

    private ScriptedEngine engine;
    private ThinkProcessDocument process;
    private ThinkEngineContext ctx;

    @BeforeEach
    void setUp() {
        engine = new ScriptedEngine();
        process = ThinkProcessDocument.builder()
                .id("p1")
                .tenantId("acme")
                .projectId("proj")
                .sessionId("s1")
                .build();
        ctx = mock(ThinkEngineContext.class);
    }

    @Test
    void queuedInputMidTurn_yieldsAtTheNextLoopBoundary() {
        ScriptedEngine pickup = new PickupEngine();
        pickup.replyText = "refactored the parser";
        when(ctx.hasPending()).thenReturn(true);

        StructuredActionEngine.ActionLoopResult result = runOn(pickup, 4);

        assertThat(result.isPendingInput()).isTrue();
        assertThat(pickup.llmRounds)
                .as("the running round finishes, the yield happens at the next boundary")
                .isEqualTo(1);
        assertThat(result.fallbackText())
                .as("the in-turn narration rides out as the next turn's record")
                .isEqualTo("refactored the parser");
    }

    @Test
    void firstIteration_neverYields() {
        ScriptedEngine pickup = new PickupEngine();
        when(ctx.hasPending()).thenReturn(true);

        StructuredActionEngine.ActionLoopResult result = runOn(pickup, 1);

        assertThat(result.isPendingInput())
                .as("a turn handed its input directly must get a full round first")
                .isFalse();
        assertThat(pickup.llmRounds).isEqualTo(1);
    }

    @Test
    void pickupDisabled_runsTheTurnUnaffected() {
        ScriptedEngine disabled = new ScriptedEngine() {
            @Override
            protected boolean pickupPendingMidLoop(ThinkProcessDocument process) {
                return false;
            }
        };
        when(ctx.hasPending()).thenReturn(true);

        StructuredActionEngine.ActionLoopResult result = runOn(disabled, 3);

        assertThat(result.isPendingInput()).isFalse();
        assertThat(disabled.llmRounds)
                .as("deterministic runners keep turn-atomic processing")
                .isEqualTo(3);
    }

    @Test
    void noQueuedInput_runsTheTurnUnaffected() {
        ScriptedEngine pickup = new PickupEngine();
        when(ctx.hasPending()).thenReturn(false);

        StructuredActionEngine.ActionLoopResult result = runOn(pickup, 3);

        assertThat(result.isPendingInput()).isFalse();
        assertThat(pickup.llmRounds).isEqualTo(3);
    }

    @Test
    void baseEngine_doesNotPickUpByDefault() {
        // The capability is opt-in: engines that never override the hook
        // (hactar, wowbagger, vogon, workers) must keep their behaviour.
        when(ctx.hasPending()).thenReturn(true);

        StructuredActionEngine.ActionLoopResult result = runOn(engine, 3);

        assertThat(result.isPendingInput()).isFalse();
        assertThat(engine.llmRounds).isEqualTo(3);
    }

    @Test
    void pendingInputResult_isNeitherActionNorFallback() {
        StructuredActionEngine.ActionLoopResult result =
                StructuredActionEngine.ActionLoopResult.pendingInput("notes", 2);
        assertThat(result.isAction()).isFalse();
        assertThat(result.isFallback())
                .as("consumers branch on isPendingInput(); a fallback verdict would trigger the judge")
                .isFalse();
        assertThat(result.isInterrupted()).isFalse();
        assertThat(result.toolInvocations()).isEqualTo(2);
    }

    private StructuredActionEngine.ActionLoopResult runOn(ScriptedEngine target, int maxIters) {
        return target.runStructuredActionLoop(
                mock(AiChat.class),
                tools -> List.of(),
                new ArrayList<>(List.of(dev.langchain4j.data.message.UserMessage.from("hello"))),
                ctx,
                process,
                maxIters,
                "default:fast",
                /*maxCorrections*/ 2,
                System.currentTimeMillis() + 60_000L);
    }

    /**
     * Scripted engine: every LLM round returns free text and no tool calls,
     * so the loop runs into the next iteration (where the loop-head checks
     * live) until the budget ends.
     */
    private static class ScriptedEngine extends StructuredActionEngine {

        private int llmRounds = 0;
        private String replyText = "working on it";

        ScriptedEngine() {
            super(
                    new StreamingProperties(),
                    mock(LlmCallTracker.class),
                    JsonMapper.builder().build(),
                    mock(SystemPromptComposer.class),
                    mock(ShootyGuardService.class),
                    mock(ActionLoopJudgeService.class),
                    mock(ThinkProcessService.class),
                    new TurnContextHandlerRegistry(List.<TurnContextHandler>of()),
                    mock(de.mhus.vance.brain.ai.attachment.AttachedUserMessageComposer.class));
        }

        @Override
        protected AiMessage streamOneIteration(
                AiChat aiChat,
                dev.langchain4j.model.chat.request.ChatRequest request,
                ThinkEngineContext ctx,
                ThinkProcessDocument process,
                String modelAlias) {
            llmRounds++;
            return AiMessage.from(replyText);
        }

        @Override
        public String name() {
            return "scripted-engine";
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
        public void suspend(ThinkProcessDocument p, ThinkEngineContext ctx) {
            // stub
        }

        @Override
        public void stop(ThinkProcessDocument p, ThinkEngineContext ctx) {
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
            return "test";
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
        protected ActionTurnOutcome handleAction(
                EngineAction action, ThinkProcessDocument process, ThinkEngineContext ctx) {
            return new ActionTurnOutcome("done", true);
        }
    }

    /** Opted-in engine — the Arthur/Eddie posture. */
    private static final class PickupEngine extends ScriptedEngine {

        @Override
        protected boolean pickupPendingMidLoop(ThinkProcessDocument process) {
            return true;
        }
    }
}
