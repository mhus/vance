package de.mhus.vance.brain.marvin;

import de.mhus.vance.api.marvin.WorkerPhase;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * One phase's LLM call wrapped in a bounded parse-error correction
 * loop.
 *
 * <p>Motivation (measured, benchmark MarvinWorkerBenchmark
 * 2026-09-12): a VALIDATE reply with a near-miss verdict token
 * ({@code NEEDS_MORE_DATA} instead of {@code NEED_MORE_DATA})
 * previously killed the node outright — the whole deep-think run
 * failed on a single-letter drift and the CONCLUDE candidate was
 * lost. Every other structured surface in the system corrects
 * instead: {@code StructuredActionEngine} handles malformed JSON
 * and unknown action types by telling the model what failed and
 * retrying within a correction budget ("preserves work, never
 * crashes"), and Ford runs a validation-gated correction loop for
 * thin data-relay replies. Marvin's phase machine gets the same
 * treatment here: a reply that fails to parse is fed back with the
 * parser's error and retried, up to {@code maxCorrections} times;
 * after that the LAST reply is returned unchanged and the caller
 * routes it into the documented parse-failure path (spec §17 —
 * node FAILED with parse error), so the spec's terminal semantics
 * are untouched, only reached less often.
 */
class PhaseCorrectionLoop {

    /** One LLM round trip: receives the full message list, returns the reply text. */
    interface Llm {
        String chat(List<ChatMessage> messages);
    }

    private final PhaseOutputParser parser;
    private final int maxCorrections;

    PhaseCorrectionLoop(PhaseOutputParser parser, int maxCorrections) {
        this.parser = parser;
        this.maxCorrections = maxCorrections;
    }

    /**
     * Runs the phase call with correction. The returned text is the
     * first reply that parses — or the last reply after the budget
     * is exhausted, which the caller is expected to route to
     * FinishFailed.
     */
    String run(WorkerPhase phase, List<ChatMessage> baseMessages, Llm llm) {
        return run(phase, baseMessages, llm, error -> {});
    }

    /**
     * As {@link #run(WorkerPhase, List, Llm)}, with a callback that
     * receives each parse error right before the correction
     * re-prompt is sent — for engine-side logging.
     */
    String run(
            WorkerPhase phase,
            List<ChatMessage> baseMessages,
            Llm llm,
            java.util.function.Consumer<String> onCorrection) {
        List<ChatMessage> messages = new ArrayList<>(baseMessages);
        for (int corrections = 0; ; corrections++) {
            String lastText = llm.chat(messages);
            String error = parser.parseError(phase, lastText);
            if (error == null || corrections >= maxCorrections) {
                return lastText;
            }
            onCorrection.accept(error);
            messages.add(AiMessage.from(lastText));
            messages.add(UserMessage.from(correctionMessage(phase, error)));
        }
    }

    private static String correctionMessage(WorkerPhase phase, String error) {
        return "Your last reply for phase " + phase + " failed to parse: " + error
                + "\n\nReply again with a single JSON object that follows the phase schema exactly"
                + " — use the exact token spellings from the instructions.";
    }
}
