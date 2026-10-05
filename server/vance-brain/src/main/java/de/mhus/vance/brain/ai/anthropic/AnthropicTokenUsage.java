package de.mhus.vance.brain.ai.anthropic;

import de.mhus.vance.brain.ai.CacheAwareTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import lombok.Getter;

/**
 * langchain4j's {@link TokenUsage} doesn't expose Anthropic's
 * cache-aware counters ({@code cache_creation_input_tokens},
 * {@code cache_read_input_tokens}). Subclassing keeps the existing
 * {@code inputTokenCount} / {@code outputTokenCount} contract while
 * letting cache-aware consumers — {@code LlmTraceRecorder}, the Insights
 * aggregations — pull the extra counters via
 * {@code instanceof CacheAwareTokenUsage}.
 *
 * <p>The standard counters carry the <i>uncached</i> input + output
 * tokens (i.e. what's billed at full input price). Cache tokens are
 * additive — total tokens billed for a call equals
 * {@code inputTokenCount + cacheCreationInputTokens × 1.25 +
 * cacheReadInputTokens × 0.1 + outputTokenCount}.
 *
 * <p>The 1h-TTL share of the creation counter travels separately
 * ({@link #cacheCreation1hInputTokens()}): 1h writes cost ~2× the 5m write
 * rate, so the ledger prices the two shares differently. Anthropic reports
 * the split as {@code usage.cache_creation.ephemeral_1h_input_tokens}; a
 * payload without the split keeps {@code 0} here and is priced at the 5m
 * rate.
 */
@Getter
public class AnthropicTokenUsage extends TokenUsage implements CacheAwareTokenUsage {

    private final long cacheCreationInputTokens;
    private final long cacheReadInputTokens;
    private final long cacheCreation1hInputTokens;

    public AnthropicTokenUsage(
            int inputTokens, int outputTokens, long cacheCreationInputTokens, long cacheReadInputTokens) {
        this(inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens, 0L);
    }

    public AnthropicTokenUsage(
            int inputTokens,
            int outputTokens,
            long cacheCreationInputTokens,
            long cacheReadInputTokens,
            long cacheCreation1hInputTokens) {
        super(inputTokens, outputTokens, inputTokens + outputTokens);
        this.cacheCreationInputTokens = cacheCreationInputTokens;
        this.cacheReadInputTokens = cacheReadInputTokens;
        this.cacheCreation1hInputTokens = cacheCreation1hInputTokens;
    }

    @Override
    public long cacheCreationInputTokens() {
        return cacheCreationInputTokens;
    }

    @Override
    public long cacheReadInputTokens() {
        return cacheReadInputTokens;
    }

    @Override
    public long cacheCreation1hInputTokens() {
        return cacheCreation1hInputTokens;
    }
}
