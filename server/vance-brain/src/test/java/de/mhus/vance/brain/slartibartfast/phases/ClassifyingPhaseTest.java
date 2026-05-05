package de.mhus.vance.brain.slartibartfast.phases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.Claim;
import de.mhus.vance.api.slartibartfast.ClassificationKind;
import de.mhus.vance.api.slartibartfast.EvidenceSource;
import de.mhus.vance.api.slartibartfast.EvidenceType;
import de.mhus.vance.api.slartibartfast.PhaseIteration;
import de.mhus.vance.api.slartibartfast.Rationale;
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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link ClassifyingPhase}. Uses
 * {@link ScriptedChatModel} so the LLM round-trip is deterministic
 * and tests focus on parse/validate/audit behaviour.
 */
class ClassifyingPhaseTest {

    private LlmCallTracker llmCallTracker;
    private ClassifyingPhase phase;

    private ScriptedChatModel chatModel;
    private ThinkProcessDocument process;
    private ThinkEngineContext ctx;

    @BeforeEach
    void setUp() {
        EngineChatFactory engineChatFactory = mock(EngineChatFactory.class);
        llmCallTracker = mock(LlmCallTracker.class);

        chatModel = new ScriptedChatModel();
        AiChat aiChat = mock(AiChat.class);
        when(aiChat.chatModel()).thenReturn(chatModel);

        AiChatConfig cfg = new AiChatConfig("test", "scripted", "stub-key");
        ChatBehavior behavior = ChatBehavior.single(cfg);
        EngineChatFactory.EngineChatBundle bundle =
                new EngineChatFactory.EngineChatBundle(aiChat, behavior);
        when(engineChatFactory.forProcess(any(), any(), any())).thenReturn(bundle);

        phase = new ClassifyingPhase(
                engineChatFactory, llmCallTracker, JsonMapper.builder().build());

        process = new ThinkProcessDocument();
        process.setId("proc-1");
        process.setTenantId("acme");
        process.setProjectId("test-project");
        process.setSessionId("sess-1");
        ctx = mock(ThinkEngineContext.class);
    }

    @Test
    void emptySources_skipsLlmCallsAndAppendsIteration() {
        ArchitectState state = ArchitectState.builder()
                .runId("run1").build();

        phase.execute(state, process, ctx);

        assertThat(state.getEvidenceClaims()).isEmpty();
        assertThat(state.getIterations())
                .filteredOn(it -> it.getPhase() == ArchitectStatus.CLASSIFYING)
                .hasSize(1);
        assertThat(chatModel.callCount()).isEqualTo(0);
    }

    @Test
    void singleSource_extractsClaimsAndLinksRationales() {
        chatModel.script(List.of("""
                {
                  "claims": [
                    {
                      "text": "Manuals leben unter manuals/",
                      "classification": "FACT",
                      "quote": "Manuals leben unter manuals/",
                      "rationale": null
                    },
                    {
                      "text": "Lange Sätze ohne Pointe vermeiden",
                      "classification": "OPINION",
                      "quote": null,
                      "rationale": "Stilempfehlung, nicht messbarer Constraint"
                    }
                  ]
                }
                """));

        ArchitectState state = withSources(source("ev1", "manuals/x.md", "..."));

        phase.execute(state, process, ctx);

        assertThat(state.getFailureReason()).isNull();
        assertThat(state.getEvidenceClaims())
                .extracting(Claim::getId)
                .containsExactly("cl1", "cl2");
        assertThat(state.getEvidenceClaims())
                .extracting(Claim::getClassification)
                .containsExactly(ClassificationKind.FACT, ClassificationKind.OPINION);
        assertThat(state.getEvidenceClaims())
                .extracting(Claim::getSourceId)
                .containsOnly("ev1");

        // FACT carries no classificationRationaleId, OPINION must.
        assertThat(state.getEvidenceClaims().get(0).getClassificationRationaleId())
                .isNull();
        assertThat(state.getEvidenceClaims().get(1).getClassificationRationaleId())
                .isNotNull();

        // The rationale id resolves in the pool with inferredAt=CLASSIFYING.
        String rid = state.getEvidenceClaims().get(1).getClassificationRationaleId();
        assertThat(state.getRationales())
                .anySatisfy(r -> {
                    assertThat(r.getId()).isEqualTo(rid);
                    assertThat(r.getInferredAt())
                            .isEqualTo(ArchitectStatus.CLASSIFYING);
                    assertThat(r.getSourceRefs()).contains("ev1");
                });
    }

    @Test
    void multipleSources_eachGetsOneCall_claimsLinkedToCorrectSource() {
        chatModel.script(List.of(
                """
                {"claims":[{"text":"a-claim","classification":"FACT","quote":null,"rationale":null}]}
                """,
                """
                {"claims":[{"text":"b-claim","classification":"FACT","quote":null,"rationale":null}]}
                """));

        ArchitectState state = withSources(
                source("ev1", "manuals/a.md", "content-a"),
                source("ev2", "manuals/b.md", "content-b"));

        phase.execute(state, process, ctx);

        assertThat(state.getEvidenceClaims()).hasSize(2);
        assertThat(state.getEvidenceClaims().get(0).getSourceId()).isEqualTo("ev1");
        assertThat(state.getEvidenceClaims().get(1).getSourceId()).isEqualTo("ev2");
        assertThat(chatModel.callCount()).isEqualTo(2);
    }

    @Test
    void invalidJsonThenValid_retriesAndPasses() {
        chatModel.script(List.of(
                "Here are some claims: 1) X 2) Y",  // invalid
                """
                {"claims":[{"text":"x","classification":"FACT","quote":null,"rationale":null}]}
                """));

        ArchitectState state = withSources(source("ev1", "manuals/x.md", "..."));
        phase.execute(state, process, ctx);

        assertThat(state.getFailureReason()).isNull();
        assertThat(state.getEvidenceClaims()).hasSize(1);
        assertThat(state.getLlmCallRecords()).hasSize(2); // one retry recorded
    }

    @Test
    void nonFactWithoutRationale_isRejectedByValidator() {
        // First reply: OPINION without rationale → invalid.
        // Second reply: same but with rationale → valid.
        chatModel.script(List.of(
                """
                {"claims":[{"text":"x","classification":"OPINION","quote":null,"rationale":null}]}
                """,
                """
                {"claims":[{"text":"x","classification":"OPINION","quote":null,"rationale":"because preference"}]}
                """));

        ArchitectState state = withSources(source("ev1", "manuals/x.md", "..."));
        phase.execute(state, process, ctx);

        assertThat(state.getFailureReason()).isNull();
        assertThat(state.getEvidenceClaims()).hasSize(1);
        assertThat(state.getEvidenceClaims().get(0).getClassificationRationaleId())
                .isNotNull();
    }

    @Test
    void invalidEnumPastBudget_failsPhaseWithReasonAndPersistsIteration() {
        String bad = """
                {"claims":[{"text":"x","classification":"OK","quote":null,"rationale":null}]}
                """;
        chatModel.script(List.of(bad, bad, bad));

        ArchitectState state = withSources(source("ev1", "manuals/x.md", "..."));
        phase.execute(state, process, ctx);

        assertThat(state.getFailureReason()).contains("CLASSIFYING failed at source");
        assertThat(state.getFailureReason()).contains("classification 'OK' ungültig");
        assertThat(state.getIterations())
                .filteredOn(it -> it.getPhase() == ArchitectStatus.CLASSIFYING)
                .extracting(PhaseIteration::getOutcome)
                .containsExactly(PhaseIteration.IterationOutcome.FAILED);
    }

    @Test
    void reExecute_replacesClaimsAndClassifyingRationales_keepsFramingRationales() {
        chatModel.script(List.of(
                """
                {"claims":[{"text":"first","classification":"FACT","quote":null,"rationale":null}]}
                """,
                """
                {"claims":[{"text":"second","classification":"FACT","quote":null,"rationale":null}]}
                """));

        ArchitectState state = withSources(source("ev1", "manuals/x.md", "..."));
        // Pre-populate a FRAMING-tier rationale that must survive
        // the CLASSIFYING re-run.
        state.setRationales(new ArrayList<>(List.of(
                Rationale.builder()
                        .id("rt-framing-1")
                        .text("from framing")
                        .inferredAt(ArchitectStatus.FRAMING).build())));

        phase.execute(state, process, ctx);
        // Mutate sources to look like recovery rollback re-ran
        // GATHERING, then CLASSIFYING runs again.
        phase.execute(state, process, ctx);

        assertThat(state.getEvidenceClaims())
                .extracting(Claim::getText)
                .containsExactly("second");
        assertThat(state.getRationales())
                .extracting(Rationale::getId)
                .contains("rt-framing-1");
        // No CLASSIFYING-tier rationale lingers from the first run
        // (cl1/cl2 from first execute are gone, so are their
        // rationales). FACT claims have no rationale anyway, so we
        // only assert via inferredAt=CLASSIFYING count = 0 when
        // both passes used FACT-only.
        assertThat(state.getRationales())
                .filteredOn(r -> r.getInferredAt()
                        == ArchitectStatus.CLASSIFYING)
                .isEmpty();
    }

    @Test
    void llmCallTrackerInvokedPerCall() {
        chatModel.script(List.of(
                """
                {"claims":[{"text":"x","classification":"FACT","quote":null,"rationale":null}]}
                """));

        ArchitectState state = withSources(source("ev1", "manuals/x.md", "..."));
        phase.execute(state, process, ctx);

        verify(llmCallTracker, atLeastOnce())
                .record(any(), any(), any(Long.class), any());
    }

    // ──────────────────── helpers ────────────────────

    private static ArchitectState withSources(EvidenceSource... sources) {
        return ArchitectState.builder()
                .runId("run1")
                .evidenceSources(new ArrayList<>(List.of(sources)))
                .auditLlmCalls(true)
                .build();
    }

    private static EvidenceSource source(String id, String path, String content) {
        return EvidenceSource.builder()
                .id(id)
                .type(EvidenceType.MANUAL)
                .path(path)
                .content(content)
                .build();
    }

    /**
     * Minimal ChatModel stand-in that returns scripted responses
     * in order. Tracks call count for assertions.
     */
    private static class ScriptedChatModel implements ChatModel {
        private final java.util.Deque<String> responses = new java.util.ArrayDeque<>();
        private int callCount;

        void script(List<String> entries) {
            responses.clear();
            responses.addAll(entries);
            callCount = 0;
        }

        int callCount() { return callCount; }

        @Override
        public ChatResponse chat(ChatRequest request) {
            callCount++;
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
