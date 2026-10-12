package de.mhus.vance.brain.ai.decision;

import de.mhus.vance.api.zem.ZemAnswerDto;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Raw outcome of one provider call, before the service adds computed
 * confidences and latency.
 *
 * @param model        model id as reported by the provider
 * @param answers      one per question, in request order
 * @param inputTokens  reported input tokens, if any
 * @param outputTokens reported output tokens, if any (not billed by any
 *                     provider seen so far)
 * @param costUsd      vendor-reported cost, if any
 * @param requestId    vendor generation id, if any (OpenRouter)
 */
public record DecisionResult(
        String model,
        List<ZemAnswerDto> answers,
        @Nullable Long inputTokens,
        @Nullable Long outputTokens,
        @Nullable Double costUsd,
        @Nullable String requestId) {

    public DecisionResult {
        answers = List.copyOf(answers);
    }
}
