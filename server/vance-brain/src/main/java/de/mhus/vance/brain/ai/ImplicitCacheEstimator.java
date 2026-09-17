package de.mhus.vance.brain.ai;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.output.TokenUsage;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Detects prompt-cache savings the provider keeps to itself.
 *
 * <p>Some gateways serve a request from their own prefix cache and report
 * {@code prompt_tokens} as the <i>uncached tail only</i> — without
 * {@code prompt_tokens_details.cached_tokens} and without the full count.
 * The savings are real (the caller is billed for the tail, not the whole
 * prompt), but a client that meters from the reported usage sees nothing:
 * observed as a 78k-token request reporting 226 input tokens on a
 * tail-billing gateway route, with zero cache counters anywhere.
 *
 * <p><b>The inference, not the gateway.</b> This class contains no
 * provider name and no special case: whenever a call reports
 * <i>significantly less</i> input than the request actually carried, and
 * the usage carries no cache counters of its own, the difference was
 * served from a cache the provider did not itemize. Any provider that
 * reports its cache counters properly ({@link CacheAwareTokenUsage})
 * never triggers this — measured and estimated numbers stay disjoint.
 *
 * <p><b>Estimate, clearly labeled.</b> The request volume is estimated
 * from characters ({@link #CHARS_PER_TOKEN}), not tokenized — the result
 * is a lower-confidence number and is booked under its own
 * {@code implicitCacheReadTokens} field, never mixed into the
 * provider-reported {@code cacheReadTokens}. It carries no cost: the
 * provider billed the tail, and the ledger's cost estimate is built from
 * the reported tokens, which is the amount actually charged.
 */
final class ImplicitCacheEstimator {

    /**
     * Characters per input token for the volume estimate. Calibrated
     * against gateway-reported token counts for the mixed German/markdown
     * prompts Vance sends (~3.5–4.5 depending on content); the detection
     * threshold absorbs the tolerance, the estimate does not need to.
     */
    static final double CHARS_PER_TOKEN = 4.0;

    /**
     * Only report implicit cache when the reported input is below this
     * fraction of the estimated volume. A cache effect half the prompt is
     * unambiguous even at ±30% estimation error; a small gap usually is
     * estimation error.
     */
    static final double REPORTED_FRACTION_THRESHOLD = 0.5;

    /** And the absolute gap must exceed this — tokens, before it is worth a column. */
    static final int MIN_GAP_TOKENS = 1000;

    private ImplicitCacheEstimator() {}

    /**
     * Estimated tokens the provider served from an unreported cache —
     * {@code 0} when the call is not a clear case.
     *
     * @param request the request as sent (messages + tool schemas are the
     *                volume that had to arrive one way or another)
     * @param usage   the provider-reported usage after cache-aware
     *                normalization; {@code null} means nothing was
     *                reported at all and nothing can be inferred
     */
    static int estimate(@Nullable ChatRequest request, @Nullable TokenUsage usage) {
        if (request == null || usage == null) return 0;
        Integer reportedIn = usage.inputTokenCount();
        if (reportedIn == null || reportedIn <= 0) return 0;
        // Providers that itemize their cache are measured — no estimate
        // on top of a measurement.
        if (usage instanceof CacheAwareTokenUsage) return 0;
        long volumeChars = messageChars(request.messages()) + toolsChars(request.toolSpecifications());
        int estimatedIn = (int) (volumeChars / CHARS_PER_TOKEN);
        if (estimatedIn <= 0) return 0;
        if (reportedIn > estimatedIn * REPORTED_FRACTION_THRESHOLD) return 0;
        int gap = estimatedIn - reportedIn;
        if (gap < MIN_GAP_TOKENS) return 0;
        return gap;
    }

    /** Plain text length of the request messages — same extraction the trace rows use. */
    private static long messageChars(@Nullable List<ChatMessage> messages) {
        if (messages == null) return 0;
        long chars = 0;
        for (ChatMessage msg : messages) {
            String text = LlmTraceRecorder.textOf(msg);
            if (text != null) chars += text.length();
        }
        return chars;
    }

    /** Serialized tool schemas — the array leads the token stream, its size is part of the volume. */
    private static long toolsChars(@Nullable List<ToolSpecification> specs) {
        return specs == null || specs.isEmpty() ? 0 : LlmTraceRecorder.estimateToolsBytes(specs);
    }
}
