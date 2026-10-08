package de.mhus.vance.addon.brain.nutrimat.macchiato;

import static de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness.text;
import static de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopInputs;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopStats;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.TurnOutcome;
import de.mhus.vance.addon.brain.nutrimat.NutrimatInterruptedException;
import de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * macchiato's own loop: two models in one turn. Each role gets its own
 * scripted chat so the test sees which model ran which round and with
 * which tool surface; the role resolution ({@code inputsFor}) is replaced
 * by a spec → inputs map instead of a live chat factory.
 */
class NutrimatMacchiatoTest {

    private static final ToolSpecification DOC_READ = ToolSpecification.builder()
            .name("doc_read")
            .description("read a document")
            .build();

    private final NutrimatLoopHarness h = new NutrimatLoopHarness();
    private final List<String> sequence = new ArrayList<>();
    private final List<ChatMessage> messages = new ArrayList<>(List.of(UserMessage.from("do the thing")));

    private final ScriptedChat primary = new ScriptedChat("primary");
    private final ScriptedChat worker = new ScriptedChat("worker");
    private final ScriptedChat answer = new ScriptedChat("answer");

    private final Map<String, LoopInputs> bySpec = new HashMap<>();

    private final LoopInputs primaryInputs = primary.inputs();

    // Positional args on purpose — a constructor change must break compile.
    private final NutrimatMacchiato engine =
            new NutrimatMacchiato(
                    h.thinkProcessService,
                    h.objectMapper,
                    h.streamingProperties,
                    null,
                    h.llmCallTracker,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    h.turnContextHandlers,
                    null,
                    h.notifications) {
                @Override
                LoopInputs inputsFor(
                        ThinkProcessDocument process,
                        ThinkEngineContext ctx,
                        LoopInputs in,
                        @Nullable String modelSpec) {
                    if (modelSpec == null) {
                        return super.inputsFor(process, ctx, in, null);
                    }
                    if ("broken".equals(modelSpec)) {
                        throw new IllegalStateException("no such alias");
                    }
                    return bySpec.get(modelSpec);
                }
            };

    NutrimatMacchiatoTest() {
        bySpec.put("w", worker.inputs());
        bySpec.put("a", answer.inputs());
    }

    private TurnOutcome run() {
        return engine.runLoop(h.process, h.ctx, primaryInputs, new LoopStats());
    }

    private void params(String key, Object value) {
        h.process.getEngineParams().put(key, value);
    }

    @Test
    void workerFirst_workerDoesTheToolRounds_answerModelWritesTheReplyOnceWithoutTools() {
        params(NutrimatMacchiato.PARAM_WORKER_MODEL, "w");
        params(NutrimatMacchiato.PARAM_ANSWER_MODEL, "a");
        worker.script(toolCall("{\"n\":1}", ""), text("worker draft"));
        answer.script(text("the final answer"));

        TurnOutcome out = run();

        assertThat(out.finalText()).isEqualTo("the final answer");
        assertThat(out.recovered()).isFalse();
        assertThat(out.awaitingUserInput())
                .as("an answer leaves the process IDLE")
                .isFalse();
        assertThat(sequence).containsExactly("worker", "worker", "answer");
        assertThat(worker.requests)
                .allSatisfy(r -> assertThat(r.toolSpecifications()).isNotEmpty());
        assertThat(answer.requests.getFirst().toolSpecifications()).isNullOrEmpty();
        assertThat(h.contents()).contains("worker draft");
    }

    @Test
    void plannerFirst_answerModelPlansWithoutTools_workerExecutesAndAnswers() {
        params(NutrimatMacchiato.PARAM_MODE, "planner-first");
        params(NutrimatMacchiato.PARAM_WORKER_MODEL, "w");
        params(NutrimatMacchiato.PARAM_ANSWER_MODEL, "a");
        answer.script(text("1. read\n2. answer"));
        worker.script(toolCall("{\"n\":1}", ""), text("the worker's answer"));

        TurnOutcome out = run();

        assertThat(out.finalText()).isEqualTo("the worker's answer");
        assertThat(out.recovered()).isFalse();
        assertThat(sequence).containsExactly("answer", "worker", "worker");
        assertThat(answer.requests.getFirst().toolSpecifications()).isNullOrEmpty();
        assertThat(messages.stream()
                        .filter(m -> m instanceof AiMessage ai && "1. read\n2. answer".equals(ai.text()))
                        .count())
                .as("the plan is in the worker's context")
                .isEqualTo(1);
    }

    @Test
    void missingModelParams_bothRolesRunOnThePrimary() {
        primary.script(toolCall("{\"n\":1}", ""), text("draft"), text("final"));

        TurnOutcome out = run();

        assertThat(out.finalText()).isEqualTo("final");
        assertThat(sequence).containsExactly("primary", "primary", "primary");
        assertThat(primary.requests.get(2).toolSpecifications())
                .as("the answer round runs without tools")
                .isNullOrEmpty();
    }

    @Test
    void blankAnswerRound_fallsBackToTheWorkerDraft() {
        params(NutrimatMacchiato.PARAM_WORKER_MODEL, "w");
        params(NutrimatMacchiato.PARAM_ANSWER_MODEL, "a");
        worker.script(text("worker draft"));
        answer.script(text(" "));

        TurnOutcome out = run();

        assertThat(out.finalText()).isEqualTo("worker draft");
        assertThat(out.recovered()).isFalse();
    }

    @Test
    void unresolvableModel_failsTheTurnVisibly() {
        params(NutrimatMacchiato.PARAM_WORKER_MODEL, "broken");

        TurnOutcome out = run();

        assertThat(out.recovered()).isTrue();
        assertThat(out.finalText()).contains("could not be resolved").contains("broken");
        assertThat(sequence).isEmpty();
    }

    @Test
    void optInRoundCap_carriesThePartialOut() {
        params(NutrimatMacchiato.PARAM_WORKER_MODEL, "w");
        params(NutrimatMacchiato.PARAM_MAX_ITERATIONS, 2);
        worker.script(toolCall("{\"n\":1}", "partial progress"));

        TurnOutcome out = run();

        assertThat(out.recovered()).isTrue();
        assertThat(out.wrapPartial()).isTrue();
        assertThat(out.finalText()).isEqualTo("partial progress");
        assertThat(worker.requests).hasSize(2);
    }

    @Test
    void emptyWorkerReply_failsTheTurn() {
        params(NutrimatMacchiato.PARAM_WORKER_MODEL, "w");
        worker.script(text(""));

        TurnOutcome out = run();

        assertThat(out.recovered()).isTrue();
        assertThat(out.finalText()).contains("empty response");
    }

    @Test
    void interrupt_comesFromRound_andIsNeverSwallowed() {
        params(NutrimatMacchiato.PARAM_WORKER_MODEL, "w");
        worker.script(text("never reached"));
        when(h.thinkProcessService.isHaltRequested("p1")).thenReturn(true);

        assertThatThrownBy(this::run).isInstanceOf(NutrimatInterruptedException.class);
        assertThat(sequence).isEmpty();
    }

    /** One role's scripted streaming chat; records its requests and the call order. */
    private final class ScriptedChat {

        private final String role;
        private final AiChat aiChat = mock(AiChat.class);
        private final StreamingChatModel model = mock(StreamingChatModel.class);
        private final List<AiMessage> script = new ArrayList<>();
        final List<ChatRequest> requests = new ArrayList<>();

        ScriptedChat(String role) {
            this.role = role;
            when(aiChat.streamingChatModel()).thenReturn(model);
            doAnswer(inv -> {
                        ChatRequest request = inv.getArgument(0);
                        StreamingChatResponseHandler handler = inv.getArgument(1);
                        requests.add(request);
                        sequence.add(role);
                        AiMessage msg = script.get(Math.min(requests.size() - 1, script.size() - 1));
                        handler.onCompleteResponse(
                                ChatResponse.builder().aiMessage(msg).build());
                        return null;
                    })
                    .when(model)
                    .chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        }

        void script(AiMessage... replies) {
            script.addAll(List.of(replies));
        }

        LoopInputs inputs() {
            return new LoopInputs(aiChat, List.of(DOC_READ), h.tools, messages, false, role + "-alias", "do the thing");
        }
    }
}
