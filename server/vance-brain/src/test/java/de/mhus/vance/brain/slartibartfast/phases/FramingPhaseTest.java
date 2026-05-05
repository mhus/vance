package de.mhus.vance.brain.slartibartfast.phases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.Criterion;
import de.mhus.vance.api.slartibartfast.CriterionOrigin;
import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.api.slartibartfast.PhaseIteration;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.ChatBehavior;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link FramingPhase}. Stubs the LLM via a fake
 * {@link ChatModel} that returns scripted text — verifies the
 * phase's parse, validate, retry-on-failure and audit-append
 * behaviour without ever calling a real provider.
 */
class FramingPhaseTest {

    private EngineChatFactory engineChatFactory;
    private LlmCallTracker llmCallTracker;
    private FramingPhase phase;

    private ScriptedChatModel chatModel;
    private ThinkProcessDocument process;
    private ThinkEngineContext ctx;

    @BeforeEach
    void setUp() {
        engineChatFactory = mock(EngineChatFactory.class);
        llmCallTracker = mock(LlmCallTracker.class);

        chatModel = new ScriptedChatModel();
        AiChat aiChat = mock(AiChat.class);
        when(aiChat.chatModel()).thenReturn(chatModel);

        AiChatConfig cfg = new AiChatConfig("test", "scripted", "stub-key");
        ChatBehavior behavior = ChatBehavior.single(cfg);
        EngineChatFactory.EngineChatBundle bundle =
                new EngineChatFactory.EngineChatBundle(aiChat, behavior);
        when(engineChatFactory.forProcess(any(), any(), any())).thenReturn(bundle);

        phase = new FramingPhase(
                engineChatFactory, llmCallTracker, JsonMapper.builder().build());

        process = new ThinkProcessDocument();
        process.setId("proc-1");
        process.setTenantId("acme");
        process.setProjectId("test-project");
        process.setSessionId("sess-1");
        ctx = mock(ThinkEngineContext.class);
    }

    @Test
    void firstAttemptValid_populatesFramedGoalAndIteration() {
        chatModel.script(List.of("""
                {
                  "framed": "Schreibe eine Adams-style Kurzgeschichte",
                  "statedCriteria": [
                    {"text": "Output ist ein Essay"}
                  ],
                  "assumedCriteria": [
                    {
                      "text": "Essay wird als Document persistiert",
                      "origin": "INFERRED_CONVENTION",
                      "confidence": 0.95,
                      "rationale": "Erzeugte Inhalte werden in Vance immer als Document gespeichert"
                    }
                  ]
                }
                """));

        ArchitectState state = newState("schreib mir ein adams essay");
        phase.execute(state, process, ctx);

        assertThat(state.getFailureReason()).isNull();
        assertThat(state.getGoal()).isNotNull();
        assertThat(state.getGoal().getFramed())
                .isEqualTo("Schreibe eine Adams-style Kurzgeschichte");
        assertThat(state.getGoal().getStatedCriteria()).hasSize(1);
        assertThat(state.getGoal().getStatedCriteria().get(0).getOrigin())
                .isEqualTo(CriterionOrigin.USER_STATED);
        assertThat(state.getGoal().getAssumedCriteria()).hasSize(1);

        Criterion assumed = state.getGoal().getAssumedCriteria().get(0);
        assertThat(assumed.getOrigin()).isEqualTo(CriterionOrigin.INFERRED_CONVENTION);
        assertThat(assumed.getConfidence()).isEqualTo(0.95);
        assertThat(assumed.getRationaleId()).isNotNull();

        assertThat(state.getRationales()).hasSize(1);
        assertThat(state.getRationales().get(0).getId())
                .isEqualTo(assumed.getRationaleId());
        assertThat(state.getRationales().get(0).getInferredAt())
                .isEqualTo(ArchitectStatus.FRAMING);

        assertThat(state.getIterations()).hasSize(1);
        PhaseIteration it = state.getIterations().get(0);
        assertThat(it.getPhase()).isEqualTo(ArchitectStatus.FRAMING);
        assertThat(it.getOutcome())
                .isEqualTo(PhaseIteration.IterationOutcome.PASSED);
        assertThat(it.getLlmCallRecordId()).isNotNull();

        assertThat(state.getLlmCallRecords()).hasSize(1);
        verify(llmCallTracker, atLeastOnce())
                .record(any(), any(), any(Long.class), any());
    }

    @Test
    void invalidJsonThenValid_retriesAndPasses() {
        chatModel.script(List.of(
                "I think the user wants an essay. Here you go: an essay about robots.",
                """
                {
                  "framed": "ok",
                  "statedCriteria": [{"text": "Output ist Essay"}],
                  "assumedCriteria": []
                }
                """));

        ArchitectState state = newState("schreib essay");
        phase.execute(state, process, ctx);

        assertThat(state.getFailureReason()).isNull();
        assertThat(state.getGoal()).isNotNull();
        assertThat(state.getLlmCallRecords()).hasSize(2);
        assertThat(state.getIterations()).hasSize(1);
        assertThat(state.getIterations().get(0).getOutcome())
                .isEqualTo(PhaseIteration.IterationOutcome.PASSED);
    }

    @Test
    void invalidEnumOrigin_failsAfterMaxCorrections() {
        // origin "FOO" is invalid → never parses → exhausts budget.
        String bad = """
                {
                  "framed": "x",
                  "statedCriteria": [],
                  "assumedCriteria": [{
                    "text": "y",
                    "origin": "FOO",
                    "confidence": 0.9,
                    "rationale": "z"
                  }]
                }
                """;
        chatModel.script(List.of(bad, bad, bad));

        ArchitectState state = newState("anything");
        phase.execute(state, process, ctx);

        assertThat(state.getFailureReason())
                .contains("origin 'FOO'");
        assertThat(state.getGoal()).isNull();
        assertThat(state.getLlmCallRecords()).hasSize(3); // 1 initial + 2 retries
        assertThat(state.getIterations()).hasSize(1);
        assertThat(state.getIterations().get(0).getOutcome())
                .isEqualTo(PhaseIteration.IterationOutcome.FAILED);
    }

    @Test
    void confidenceOutOfRange_isRejected() {
        chatModel.script(List.of(
                """
                {
                  "framed": "x",
                  "statedCriteria": [],
                  "assumedCriteria": [{
                    "text": "y",
                    "origin": "INFERRED_CONVENTION",
                    "confidence": 1.5,
                    "rationale": "z"
                  }]
                }
                """,
                """
                {
                  "framed": "x",
                  "statedCriteria": [],
                  "assumedCriteria": [{
                    "text": "y",
                    "origin": "INFERRED_CONVENTION",
                    "confidence": 0.85,
                    "rationale": "z"
                  }]
                }
                """));

        ArchitectState state = newState("anything");
        phase.execute(state, process, ctx);

        assertThat(state.getFailureReason()).isNull();
        assertThat(state.getLlmCallRecords()).hasSize(2);
        assertThat(state.getGoal().getAssumedCriteria()).hasSize(1);
        assertThat(state.getGoal().getAssumedCriteria().get(0).getConfidence())
                .isEqualTo(0.85);
    }

    @Test
    void engineAssignsIds_evenWhenLlmOmitsThem() {
        chatModel.script(List.of("""
                {
                  "framed": "x",
                  "statedCriteria": [
                    {"text": "a"},
                    {"text": "b"}
                  ],
                  "assumedCriteria": [
                    {"text": "c", "origin": "INFERRED_CONVENTION", "confidence": 0.9, "rationale": "r"}
                  ]
                }
                """));

        ArchitectState state = newState("anything");
        phase.execute(state, process, ctx);

        assertThat(state.getGoal().getStatedCriteria())
                .extracting(Criterion::getId)
                .containsExactly("cr1", "cr2");
        assertThat(state.getGoal().getAssumedCriteria())
                .extracting(Criterion::getId)
                .containsExactly("cr3");
        assertThat(state.getRationales())
                .extracting(de.mhus.vance.api.slartibartfast.Rationale::getId)
                .containsExactly("rt1");
    }

    @Test
    void usesAssumedOriginUserStatedReJection_drivesRetry() {
        // Origin USER_STATED is forbidden in assumedCriteria — the
        // validator should reject and re-prompt.
        chatModel.script(List.of(
                """
                {
                  "framed": "x",
                  "statedCriteria": [],
                  "assumedCriteria": [{
                    "text": "y",
                    "origin": "USER_STATED",
                    "confidence": 0.9,
                    "rationale": "z"
                  }]
                }
                """,
                """
                {
                  "framed": "x",
                  "statedCriteria": [{"text": "y"}],
                  "assumedCriteria": []
                }
                """));

        ArchitectState state = newState("anything");
        phase.execute(state, process, ctx);

        assertThat(state.getFailureReason()).isNull();
        assertThat(state.getGoal().getStatedCriteria()).hasSize(1);
        assertThat(state.getLlmCallRecords()).hasSize(2);
    }

    private static ArchitectState newState(String userDescription) {
        return ArchitectState.builder()
                .runId("test-run")
                .userDescription(userDescription)
                .outputSchemaType(OutputSchemaType.VOGON_STRATEGY)
                .build();
    }

    /**
     * Minimal {@link ChatModel} stand-in that returns scripted
     * responses in order. Each call to {@link #chat(ChatRequest)}
     * pops the next entry; running off the script throws.
     */
    private static class ScriptedChatModel implements ChatModel {
        private final java.util.Deque<String> responses = new java.util.ArrayDeque<>();

        void script(List<String> entries) {
            responses.clear();
            responses.addAll(entries);
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            if (responses.isEmpty()) {
                throw new IllegalStateException("ScriptedChatModel: no more scripted responses");
            }
            String text = responses.pop();
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(text))
                    .build();
        }
    }
}
