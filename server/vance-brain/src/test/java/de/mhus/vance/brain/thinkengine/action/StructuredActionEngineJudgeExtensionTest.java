package de.mhus.vance.brain.thinkengine.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.ModelCapability;
import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ai.attachment.AttachedUserMessageComposer;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

/**
 * What a judge-approved extension round is handed.
 *
 * <p>An extension is not a fresh turn — it continues the same one. Both
 * the turn's active-skill tools ({@code extraTools}) and its attachment
 * context therefore have to ride along: the loop dispatches through the
 * same {@link ContextToolsApi}, so a skill tool that stopped being in the
 * allow-set would come back as "not available to this engine" mid-turn,
 * and a dropped attachment context silently stops tool-produced images
 * from reaching the model.
 *
 * <p>Exercised at the {@code runStructuredActionLoop} seam: the real loop
 * needs a scripted model, a judge and the streaming stack, none of which
 * say anything about argument propagation.
 */
class StructuredActionEngineJudgeExtensionTest {

    private static final Set<String> SKILL_TOOLS = Set.of("skill_tool_a", "skill_tool_b");

    private ActionLoopJudgeService judgeService;
    private RecordingEngine engine;
    private ThinkProcessDocument process;

    @BeforeEach
    void setUp() {
        judgeService = mock(ActionLoopJudgeService.class);
        engine = new RecordingEngine(judgeService);
        process = new ThinkProcessDocument();
        process.setId("proc-1");
        process.setTenantId("acme");
        process.setProjectId("proj");
    }

    private static AttachedUserMessageComposer.Context attachmentContext() {
        return new AttachedUserMessageComposer.Context(
                "acme", "proj", "proc-1", "openai:gpt-x", ProviderType.OPENAI,
                Set.of(ModelCapability.VISION));
    }

    private ActionLoopResultHolder run() {
        AttachedUserMessageComposer.Context attachments = attachmentContext();
        StructuredActionEngine.ActionLoopResult result = engine.runActionLoopWithJudge(
                mock(AiChat.class), tools -> List.of(), new ArrayList<>(),
                mock(ThinkEngineContext.class), process,
                /*maxIters*/ 6, "default:fast", /*maxCorrections*/ 2,
                List.<SteerMessage>of(), SKILL_TOOLS, attachments);
        return new ActionLoopResultHolder(result, attachments);
    }

    private record ActionLoopResultHolder(
            StructuredActionEngine.ActionLoopResult result,
            AttachedUserMessageComposer.Context attachments) {}

    @Test
    void extensionRound_keepsTheTurnsSkillTools() {
        when(judgeService.judge(any())).thenReturn(
                ActionLoopJudgeService.Judgment.extend("still working"));
        engine.scriptRounds(2);

        run();

        assertThat(engine.calls).hasSize(2);
        assertThat(engine.calls.get(1).extraTools())
                .as("extension round must see the same tool surface as the initial round")
                .isEqualTo(SKILL_TOOLS);
    }

    @Test
    void extensionRound_keepsTheAttachmentContext() {
        when(judgeService.judge(any())).thenReturn(
                ActionLoopJudgeService.Judgment.extend("still working"));
        engine.scriptRounds(2);

        ActionLoopResultHolder run = run();

        assertThat(engine.calls.get(1).attachmentContext())
                .as("without it a tool-produced image never reaches the model")
                .isSameAs(run.attachments());
    }

    @Test
    void severalExtensions_allCarryTheSurfaceForward() {
        when(judgeService.judge(any())).thenReturn(
                ActionLoopJudgeService.Judgment.extend("still working"));
        engine.scriptRounds(4);

        run();

        assertThat(engine.calls).hasSize(4);
        assertThat(engine.calls).allSatisfy(call -> {
            assertThat(call.extraTools()).isEqualTo(SKILL_TOOLS);
            assertThat(call.attachmentContext()).isNotNull();
        });
    }

    @Test
    void extensionRound_getsTheShorterBudgetAndTheSameDeadline() {
        when(judgeService.judge(any())).thenReturn(
                ActionLoopJudgeService.Judgment.extend("still working"));
        engine.scriptRounds(2);

        run();

        assertThat(engine.calls.get(0).maxIters()).isEqualTo(6);
        assertThat(engine.calls.get(1).maxIters())
                .isEqualTo(ActionLoopJudgeHelpers.JUDGE_EXTENSION_ITERS);
        assertThat(engine.calls.get(1).deadlineMs())
                .as("one wallclock for the whole turn, not one per round")
                .isEqualTo(engine.calls.get(0).deadlineMs());
    }

    @Test
    void judgeSynthesises_noExtensionRoundRuns() {
        when(judgeService.judge(any())).thenReturn(
                ActionLoopJudgeService.Judgment.synthesize("here you go", "gathered enough"));
        // Round 1 must end on max-iters — that is the only thing that
        // consults the judge at all.
        engine.scriptRounds(2);

        ActionLoopResultHolder run = run();

        assertThat(engine.calls).hasSize(1);
        assertThat(run.result().fallbackReason()).isEqualTo("judge-synthesize");
        assertThat(run.result().fallbackText()).isEqualTo("here you go");
    }

    /** One recorded {@code runStructuredActionLoop} invocation. */
    private record LoopCall(
            int maxIters,
            long deadlineMs,
            Set<String> extraTools,
            AttachedUserMessageComposer.@Nullable Context attachmentContext) {}

    /**
     * Records the arguments of every loop round and returns a
     * {@code max-iters} fallback until the scripted round count is
     * reached, then a terminal action so the while-loop ends.
     */
    private static final class RecordingEngine extends StructuredActionEngine {

        private final List<LoopCall> calls = new ArrayList<>();
        private int roundsBeforeTerminal = 1;

        RecordingEngine(ActionLoopJudgeService judgeService) {
            super(null, null, null, null, null, judgeService,
                    mock(ThinkProcessService.class), null, null);
        }

        void scriptRounds(int rounds) {
            this.roundsBeforeTerminal = rounds;
        }

        @Override
        protected ActionLoopResult runStructuredActionLoop(
                AiChat aiChat,
                Function<ContextToolsApi, List<ToolSpecification>> readToolSpecsFactory,
                List<ChatMessage> messages,
                ThinkEngineContext ctx,
                ThinkProcessDocument process,
                int maxIters,
                String modelAlias,
                int maxCorrections,
                long deadlineMs,
                Set<String> extraTools,
                AttachedUserMessageComposer.@Nullable Context attachmentContext) {
            calls.add(new LoopCall(maxIters, deadlineMs, extraTools, attachmentContext));
            if (calls.size() >= roundsBeforeTerminal) {
                return ActionLoopResult.fallback("gathered", "done-here", null, 3);
            }
            return ActionLoopResult.fallback("partial", "max-iters", null, 3);
        }

        @Override public String name() { return "recording-engine"; }
        @Override public String title() { return "Recording Engine"; }
        @Override public String description() { return "test"; }
        @Override public String version() { return "1.0.0"; }
        @Override public void start(ThinkProcessDocument p, ThinkEngineContext c) { }
        @Override public void resume(ThinkProcessDocument p, ThinkEngineContext c) { }
        @Override public void suspend(ThinkProcessDocument p, ThinkEngineContext c) { }
        @Override public void steer(ThinkProcessDocument p, ThinkEngineContext c, SteerMessage m) { }
        @Override public void stop(ThinkProcessDocument p, ThinkEngineContext c) { }

        @Override protected String actionToolName() { return "test_action"; }
        @Override protected String actionToolDescription() { return "test"; }
        @Override protected Map<String, Object> actionToolSchema() { return Map.of(); }
        @Override protected Set<String> supportedActionTypes() { return Set.of(); }
        @Override protected ActionTurnOutcome handleAction(
                EngineAction action, ThinkProcessDocument process, ThinkEngineContext ctx) {
            return null;
        }
    }
}
