package de.mhus.vance.brain.marvin;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.marvin.WorkerPhase;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link PhaseCorrectionLoop} — the bounded
 * parse-error correction around each Marvin phase call.
 *
 * <p>Regression origin (benchmark MarvinWorkerBenchmark
 * 2026-09-12): a VALIDATE reply with {@code NEEDS_MORE_DATA}
 * instead of {@code NEED_MORE_DATA} used to fail the node on the
 * first parse; the loop must feed the error back and accept the
 * corrected second reply.
 */
class PhaseCorrectionLoopTest {

    private final PhaseOutputParser parser =
            new PhaseOutputParser(JsonMapper.builder().build());

    @Test
    void validFirstReplyPassesThroughWithoutCorrection() {
        ScriptedLlm llm = new ScriptedLlm("{\"verdict\": \"PASS\", \"issues\": [], \"reason\": \"looks complete\"}");
        PhaseCorrectionLoop loop = new PhaseCorrectionLoop(parser, 2);

        String text = loop.run(WorkerPhase.VALIDATE, List.of(UserMessage.from("critique phase")), llm);

        assertThat(text).contains("PASS");
        assertThat(llm.calls).isEqualTo(1);
        assertThat(llm.allMessages).hasSize(1);
    }

    @Test
    void stubbornDriftExhaustsBudgetAndFeedsEachErrorBack() {
        // The exact drift measured in the benchmark run: the model
        // wrote NEEDS_MORE_DATA (natural prose form) — one letter off
        // the enum's NEED_MORE_DATA.
        ScriptedLlm llm = new ScriptedLlm(
                "{\"verdict\": \"NEEDS_MORE_DATA\", \"issues\": [\"missing doc\"], \"reason\": \"need more docs\"}",
                "{\"verdict\": \"NEEDS_MORE_DATA\"}",
                "{\"verdict\": \"NEEDS_MORE_DATA\", \"hint\": \"read the specs\"}");
        PhaseCorrectionLoop loop = new PhaseCorrectionLoop(parser, 2);

        String text = loop.run(WorkerPhase.VALIDATE, List.of(UserMessage.from("critique phase")), llm);

        // Budget 2 → three attempts (the initial call plus two
        // corrections); the last reply is returned even though it
        // still does not parse — the CALLER routes it to FinishFailed.
        assertThat(llm.calls).isEqualTo(3);
        assertThat(text).contains("NEEDS_MORE_DATA");
        assertThat(text).contains("read the specs");

        // The correction round-trip carries the failed reply and the
        // parser's error back to the model.
        List<ChatMessage> secondCall = llm.messagesOfCall(1);
        assertThat(secondCall).hasSize(3);
        assertThat(secondCall.get(1).toString()).contains("NEEDS_MORE_DATA");
        assertThat(secondCall.get(2).toString()).contains("failed to parse").contains("NEED_MORE_DATA");
    }

    @Test
    void correctedReplyWithinBudgetIsReturnedAndStopsTheLoop() {
        ScriptedLlm llm =
                new ScriptedLlm("not json at all", "{\"verdict\": \"HARD_FAIL\", \"reason\": \"impossible goal\"}");
        PhaseCorrectionLoop loop = new PhaseCorrectionLoop(parser, 2);

        String text = loop.run(WorkerPhase.VALIDATE, List.of(UserMessage.from("critique phase")), llm);

        assertThat(llm.calls).isEqualTo(2);
        assertThat(text).contains("HARD_FAIL");
    }

    @Test
    void correctionCallbackFiresForEachFailedReply() {
        ScriptedLlm llm = new ScriptedLlm(
                "not json",
                "{\"verdict\": \"NEEDS_MORE_DATA\"}",
                "{\"verdict\": \"PASS\", \"reason\": \"ok\"}");
        List<String> logged = new ArrayList<>();
        PhaseCorrectionLoop loop = new PhaseCorrectionLoop(parser, 2);

        String text = loop.run(
                WorkerPhase.VALIDATE, List.of(UserMessage.from("critique phase")), llm, logged::add);

        assertThat(text).contains("PASS");
        assertThat(llm.calls).isEqualTo(3);
        assertThat(logged).hasSize(2);
        assertThat(logged.get(0)).contains("not valid JSON");
        assertThat(logged.get(1)).contains("Unknown 'verdict'");
    }

    @Test
    void zeroBudgetFailsOnTheFirstReply() {
        ScriptedLlm llm = new ScriptedLlm("{\"verdict\": \"NEEDS_MORE_DATA\"}");
        PhaseCorrectionLoop loop = new PhaseCorrectionLoop(parser, 0);

        String text = loop.run(WorkerPhase.VALIDATE, List.of(UserMessage.from("critique phase")), llm);

        assertThat(llm.calls).isEqualTo(1);
        assertThat(text).contains("NEEDS_MORE_DATA");
    }

    /** LLM stub: hands out scripted replies and records what it was asked. */
    private static final class ScriptedLlm implements PhaseCorrectionLoop.Llm {

        private final List<String> replies;
        int calls;
        private final List<List<ChatMessage>> allMessages = new ArrayList<>();

        ScriptedLlm(String... replies) {
            this.replies = List.of(replies);
        }

        @Override
        public String chat(List<ChatMessage> messages) {
            allMessages.add(new ArrayList<>(messages));
            String reply = replies.get(Math.min(calls, replies.size() - 1));
            calls++;
            return reply;
        }

        List<ChatMessage> messagesOfCall(int index) {
            return allMessages.get(index);
        }
    }
}
