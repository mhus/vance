package de.mhus.vance.addon.brain.nutrimat.mokka;

import static de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness.text;
import static de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopInputs;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopStats;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.TurnOutcome;
import de.mhus.vance.addon.brain.nutrimat.NutrimatInterruptedException;
import de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * mokka's own loop: every tool round states its expectation, the next
 * message scores it; the loop counts hits and misses, forces a tool-less
 * reflection on a miss streak and reports the tally at the stop.
 */
class NutrimatMokkaTest {

    private final NutrimatLoopHarness h = new NutrimatLoopHarness();

    // Positional args on purpose — a constructor change must break compile.
    private final NutrimatMokka engine = new NutrimatMokka(
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
            h.notifications);

    private static final ToolSpecification DOC_READ =
            ToolSpecification.builder().name("doc_read").build();

    private final LoopInputs in = h.inputs().withToolSpecs(List.of(DOC_READ));

    /** Every request the scripted model received, in call order. */
    private List<ChatRequest> requests() {
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(in.aiChat().streamingChatModel(), atLeastOnce())
                .chat(captor.capture(), any(StreamingChatResponseHandler.class));
        return captor.getAllValues();
    }

    private TurnOutcome run() {
        return engine.runLoop(h.process, h.ctx, in, new LoopStats());
    }

    @SuppressWarnings("unchecked")
    private List<String> roundReports() {
        Map<String, Object> state =
                (Map<String, Object>) h.process.getEngineParams().get("nutrimatState");
        return state == null ? List.of() : (List<String>) state.getOrDefault("roundReports", List.of());
    }

    private List<String> systemTexts() {
        return in.messages().stream()
                .filter(SystemMessage.class::isInstance)
                .map(m -> ((SystemMessage) m).text())
                .toList();
    }

    private List<String> aiTexts() {
        return in.messages().stream()
                .filter(AiMessage.class::isInstance)
                .map(ChatMessage.class::cast)
                .map(m -> ((AiMessage) m).text())
                .filter(t -> t != null)
                .toList();
    }

    @Test
    void hitsAndMisses_areCounted_andTheTallyIsReportedAtTheStop() {
        h.script(
                toolCall("{\"n\":1}", "EXPECT: the pom lists three modules"),
                toolCall("{\"n\":2}", "MATCH: yes — three modules\nEXPECT: the README names the owner"),
                text("MATCH: no — no owner in the README\nThe project has three modules."));

        TurnOutcome out = run();

        assertThat(out.finalText()).isEqualTo("The project has three modules.");
        assertThat(out.awaitingUserInput())
                .as("an answer leaves the process IDLE")
                .isFalse();
        assertThat(out.recovered()).isFalse();
        assertThat(roundReports()).containsExactly("predictions: 1 hits, 1 misses, 0 missing EXPECT");
        assertThat(h.calls()).isEqualTo(3);
    }

    @Test
    void aMissStreak_forcesAReflectionRound_thatStaysInTheContext() {
        h.script(
                toolCall("{\"n\":1}", "EXPECT: the file exists"),
                toolCall("{\"n\":2}", "MATCH: no — not found\nEXPECT: it is in docs/"),
                toolCall("{\"n\":3}", "MATCH: no — not there either\nEXPECT: it is in specs/"),
                text("I assumed the file exists; it may not exist at all."),
                text("MATCH: yes — fine\nThe file does not exist."));

        TurnOutcome out = run();

        assertThat(h.calls())
                .as("three working rounds, one reflection, one answer")
                .isEqualTo(5);
        assertThat(systemTexts()).anyMatch(t -> t.contains("missed 2 times in a row"));
        assertThat(aiTexts()).contains("I assumed the file exists; it may not exist at all.");
        assertThat(roundReports())
                .contains("reflection after 2 misses: I assumed the file exists; it may not exist at all.");
        assertThat(out.finalText()).isEqualTo("The file does not exist.");
        List<ChatRequest> reqs = requests();
        assertThat(reqs.get(2).toolSpecifications()).containsExactly(DOC_READ);
        assertThat(reqs.get(3).toolSpecifications())
                .as("the reflection round runs without tools")
                .isNullOrEmpty();
        assertThat(reqs.get(4).toolSpecifications()).containsExactly(DOC_READ);
    }

    @Test
    void theMismatchThreshold_isARecipeParam() {
        h.process.getEngineParams().put("mismatchThreshold", 1);
        h.script(
                toolCall("{\"n\":1}", "EXPECT: x"),
                toolCall("{\"n\":2}", "MATCH: no — y\nEXPECT: z"),
                text("reflection text"),
                text("MATCH: yes — ok\nanswer"));

        assertThat(run().finalText()).isEqualTo("answer");
        assertThat(roundReports()).contains("reflection after 1 misses: reflection text");
    }

    @Test
    void aToolRoundWithoutExpect_getsAReminder_butIsNotBlocked() {
        h.script(toolCall("{\"n\":1}", "just looking around"), text("MATCH: yes — fine\nanswer"));

        TurnOutcome out = run();

        assertThat(out.finalText()).isEqualTo("answer");
        assertThat(systemTexts()).contains(NutrimatMokka.EXPECT_REMINDER);
        assertThat(roundReports()).containsExactly("predictions: 1 hits, 0 misses, 1 missing EXPECT");
    }

    @Test
    void theLeadingMatchLine_isCutFromTheReply() {
        assertThat(NutrimatMokka.stripMatchLine("MATCH: yes — ok\n\nThe answer."))
                .isEqualTo("The answer.");
        assertThat(NutrimatMokka.stripMatchLine("\n  match: NO because\nrest")).isEqualTo("rest");
        assertThat(NutrimatMokka.stripMatchLine("No verdict here.\nMATCH: yes"))
                .isEqualTo("No verdict here.\nMATCH: yes");
        assertThat(NutrimatMokka.stripMatchLine("MATCH: yes — only the verdict"))
                .as("a reply that is nothing but the verdict stays")
                .isEqualTo("MATCH: yes — only the verdict");
    }

    @Test
    void protocolParsing() {
        assertThat(NutrimatMokka.matchOf("MATCH: yes — fine")).isTrue();
        assertThat(NutrimatMokka.matchOf("  Match: No, wrong")).isFalse();
        assertThat(NutrimatMokka.matchOf("MATCH: maybe")).isNull();
        assertThat(NutrimatMokka.matchOf("text first\nMATCH: yes")).isNull();
        assertThat(NutrimatMokka.expectationOf("reading\nEXPECT: three modules\n"))
                .isEqualTo("three modules");
        assertThat(NutrimatMokka.expectationOf("no expectation")).isNull();
    }

    @Test
    void theOptInRoundCap_endsWithTheBestPartial() {
        h.process.getEngineParams().put("maxIterations", 2);
        String longest = "MATCH: yes — found the config\nEXPECT: the config names two hosts";
        h.script(toolCall("{\"n\":1}", "EXPECT: a config"), toolCall("{\"n\":2}", longest));

        TurnOutcome out = run();

        assertThat(h.calls()).isEqualTo(2);
        assertThat(out.recovered()).isTrue();
        assertThat(out.finalText()).as("the best (longest) partial text").isEqualTo(longest);
    }

    @Test
    void emptyReply_isAFailure() {
        h.script(text(""));

        TurnOutcome out = run();

        assertThat(out.recovered()).isTrue();
        assertThat(out.finalText()).contains("empty response");
    }

    @Test
    void failedModelCall_isAFailure() {
        h.script(text("never")).failAt(1, new IllegalStateException("provider down"));

        TurnOutcome out = run();

        assertThat(out.recovered()).isTrue();
        assertThat(out.finalText()).contains("provider down");
    }

    @Test
    void interrupt_comesFromRound_andIsNeverSwallowed() {
        h.script(text("never reached"));
        when(h.thinkProcessService.isHaltRequested("p1")).thenReturn(true);

        assertThatThrownBy(this::run)
                .isInstanceOf(NutrimatInterruptedException.class)
                .satisfies(e -> assertThat(((NutrimatInterruptedException) e).forcePause())
                        .isTrue());
        assertThat(h.calls()).isZero();
    }
}
