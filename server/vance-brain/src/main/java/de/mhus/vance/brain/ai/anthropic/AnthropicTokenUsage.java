package de.mhus.vance.brain.ai.anthropic;

import dev.langchain4j.model.output.TokenUsage;
import lombok.Getter;

/**
 * langchain4j's {@link TokenUsage} doesn't expose Anthropic's
 * cache-aware counters ({@code cache_creation_input_tokens},
 * {@code cache_read_input_tokens}). Subclassing keeps the existing
 * {@code inputTokenCount} / {@code outputTokenCount} contract while
 * letting cache-aware consumers — {@code LlmTraceRecorder}, future
 * Insights aggregations — pull the extra counters via
 * {@code instanceof AnthropicTokenUsage}.
 *
 * <p>The standard counters carry the <i>uncached</i> input + output
 * tokens (i.e. what's billed at full input price). Cache tokens are
 * additive — total tokens billed for a call equals
 * {@code inputTokenCount + cacheCreationInputTokens × 1.25 +
 * cacheReadInputTokens × 0.1 + outputTokenCount}.
 */
@Getter
public class AnthropicTokenUsage extends TokenUsage {

    private final long cacheCreationInputTokens;
    private final long cacheReadInputTokens;

    public AnthropicTokenUsage(
            int inputTokens,
            int outputTokens,
            long cacheCreationInputTokens,
            long cacheReadInputTokens) {
        super(inputTokens, outputTokens, inputTokens + outputTokens);
        this.cacheCreationInputTokens = cacheCreationInputTokens;
        this.cacheReadInputTokens = cacheReadInputTokens;
    }
}
