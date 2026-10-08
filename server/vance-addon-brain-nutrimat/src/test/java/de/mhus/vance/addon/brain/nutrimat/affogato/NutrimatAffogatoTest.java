package de.mhus.vance.addon.brain.nutrimat.affogato;

import static de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness.text;
import static de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopInputs;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopStats;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.TurnOutcome;
import de.mhus.vance.addon.brain.nutrimat.NutrimatInterruptedException;
import de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness;
import de.mhus.vance.brain.ai.light.LightLlmException;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * affogato's own loop: at every stop an external critic (who sees the tool
 * work) attacks the draft — revise sends the critique back into the loop,
 * accept makes the draft the reply.
 */
class NutrimatAffogatoTest {

    private final NutrimatLoopHarness h = new NutrimatLoopHarness();
    private final AffogatoCritic critic = mock(AffogatoCritic.class);

    // Positional args on purpose — a constructor change must break compile.
    private final NutrimatAffogato engine = new NutrimatAffogato(
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
            h.notifications,
            critic);

    private TurnOutcome run(LoopInputs in) {
        return engine.runLoop(h.process, h.ctx, in, new LoopStats());
    }

    @Test
    void accept_theDraftIsTheReply() {
        h.script(toolCall("{\"n\":1}", ""), text("the answer"));
        when(critic.critique(any(), anyString(), eq("the answer"), anyString()))
                .thenReturn(new AffogatoCritic.Verdict(true, "complete"));

        TurnOutcome out = run(h.inputs());

        assertThat(out.finalText()).isEqualTo("the answer");
        assertThat(out.awaitingUserInput())
                .as("an answer leaves the process IDLE")
                .isFalse();
        assertThat(out.recovered()).isFalse();
        assertThat(h.process.getEngineParams().get("nutrimatState"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("roundReports", List.of("critic: accept — complete"));
    }

    @Test
    void revise_sendsTheCritiqueBackIntoTheLoop() {
        h.script(text("first draft"), toolCall("{\"n\":2}", ""), text("better draft"));
        when(critic.critique(any(), anyString(), eq("first draft"), anyString()))
                .thenReturn(new AffogatoCritic.Verdict(false, "the brain module is missing"));
        when(critic.critique(any(), anyString(), eq("better draft"), anyString()))
                .thenReturn(new AffogatoCritic.Verdict(true, "ok now"));
        LoopInputs in = h.inputs();

        TurnOutcome out = run(in);

        assertThat(out.finalText()).isEqualTo("better draft");
        assertThat(h.calls()).isEqualTo(3);
        assertThat(in.messages())
                .anyMatch(m ->
                        m instanceof UserMessage u && u.singleText().startsWith("CRITIC: the brain module is missing"));
        assertThat(h.contents()).as("the rejected draft is working log").contains("first draft");
    }

    @Test
    void criticSeesTheToolWork() {
        when(h.tools.invoke(anyString(), any())).thenReturn(Map.of("found", "api, shared"));
        h.script(toolCall("{\"path\":\"modules\"}", ""), text("the answer"));
        when(critic.critique(any(), anyString(), anyString(), anyString()))
                .thenReturn(new AffogatoCritic.Verdict(true, "ok"));

        run(h.inputs());

        verify(critic)
                .critique(
                        any(),
                        eq("do the thing"),
                        eq("the answer"),
                        org.mockito.ArgumentMatchers.argThat(
                                trace -> trace.contains("doc_read") && trace.contains("api, shared")));
    }

    @Test
    void maxCritiques_thenTheNextDraftIsAcceptedWithoutCritique() {
        h.process.getEngineParams().put("maxCritiques", 1);
        h.script(text("draft one"), text("draft two"));
        when(critic.critique(any(), anyString(), anyString(), anyString()))
                .thenReturn(new AffogatoCritic.Verdict(false, "not good"));

        TurnOutcome out = run(h.inputs());

        assertThat(out.finalText()).isEqualTo("draft two");
        verify(critic, times(1)).critique(any(), anyString(), anyString(), anyString());
    }

    @Test
    void emptyReply_isAFailure_withoutAskingTheCritic() {
        h.script(text(""));

        TurnOutcome out = run(h.inputs());

        assertThat(out.recovered()).isTrue();
        assertThat(out.finalText()).contains("empty response");
        verify(critic, never()).critique(any(), anyString(), anyString(), anyString());
    }

    @Test
    void optInRoundCap_endsWithTheBestPartialWork() {
        h.process.getEngineParams().put("maxIterations", 2);
        h.script(toolCall("{\"n\":1}", "partial one"), toolCall("{\"n\":2}", "partial two, longer"));

        TurnOutcome out = run(h.inputs());

        assertThat(out.recovered()).isTrue();
        assertThat(out.finalText()).isEqualTo("partial two, longer");
        assertThat(h.calls()).isEqualTo(2);
    }

    @Test
    void interrupt_comesFromRound_andIsNeverSwallowed() {
        h.script(text("never reached"));
        when(h.thinkProcessService.isHaltRequested("p1")).thenReturn(true);

        assertThatThrownBy(() -> run(h.inputs())).isInstanceOf(NutrimatInterruptedException.class);
        assertThat(h.calls()).isZero();
    }

    // ─── the critic service ─────────────────────────────────────

    @Test
    void criticFailure_acceptsTheDraft() {
        LightLlmService light = mock(LightLlmService.class);
        when(light.callForJson(any(LightLlmRequest.class))).thenThrow(new LightLlmException("provider down"));

        AffogatoCritic.Verdict v = new AffogatoCritic(light).critique(h.process, "goal", "draft", "- doc_read() → x");

        assertThat(v.accept()).isTrue();
    }

    @Test
    void criticVerdict_isFailOpen() {
        assertThat(AffogatoCritic.verdictOf(null).accept()).isTrue();
        assertThat(AffogatoCritic.verdictOf(Map.of("verdict", "maybe")).accept())
                .isTrue();
        assertThat(AffogatoCritic.verdictOf(Map.of("verdict", "revise")).accept())
                .as("revise without a critique is unusable")
                .isTrue();
        AffogatoCritic.Verdict revise = AffogatoCritic.verdictOf(Map.of("verdict", "revise", "critique", "fix X"));
        assertThat(revise.accept()).isFalse();
        assertThat(revise.critique()).isEqualTo("fix X");
    }

    // ─── the tool trace ─────────────────────────────────────────

    @Test
    void toolTrace_pairsCallsWithResults_andCutsLongResults() {
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("c1")
                .name("doc_read")
                .arguments("{\"path\":\"a.md\"}")
                .build();
        List<ChatMessage> messages =
                List.of(AiMessage.from(List.of(call)), ToolExecutionResultMessage.from(call, "y".repeat(5000)));

        String trace = NutrimatAffogato.toolTrace(messages);

        assertThat(trace).startsWith("- doc_read({\"path\":\"a.md\"}) → ");
        assertThat(trace.length()).isLessThan(NutrimatAffogato.TRACE_RESULT_LIMIT + 100);
    }

    @Test
    void toolTrace_isCapped_keepingTheNewestCalls() {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            ToolExecutionRequest call = ToolExecutionRequest.builder()
                    .id("c" + i)
                    .name("tool_" + i)
                    .arguments("{}")
                    .build();
            messages.add(AiMessage.from(List.of(call)));
            messages.add(ToolExecutionResultMessage.from(call, "r".repeat(1000)));
        }

        String trace = NutrimatAffogato.toolTrace(messages);

        assertThat(trace.length()).isLessThanOrEqualTo(NutrimatAffogato.TRACE_TOTAL_LIMIT + 100);
        assertThat(trace).contains("tool_39").doesNotContain("tool_0(").contains("earlier tool call(s) omitted");
    }
}
