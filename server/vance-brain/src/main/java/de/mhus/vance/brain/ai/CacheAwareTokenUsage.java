package de.mhus.vance.brain.ai;

/**
 * Capability-marker for {@link dev.langchain4j.model.output.TokenUsage}
 * subclasses that expose prompt-cache counters. Adapters whose backend
 * reports cached input tokens (Anthropic's {@code cache_creation_input_tokens}
 * / {@code cache_read_input_tokens}, OpenAI's
 * {@code prompt_tokens_details.cached_tokens}, …) implement this on
 * their {@code TokenUsage}-subclass so the trace layer can read counters
 * uniformly via {@code usage instanceof CacheAwareTokenUsage}.
 *
 * <p>Counters are <b>additive</b> to {@code inputTokenCount}: the
 * standard {@code TokenUsage.inputTokenCount} carries uncached input,
 * cache-creation and cache-read tokens come on top. Total billable
 * input = {@code inputTokenCount + cacheCreation + cacheRead} (with
 * provider-specific multipliers — Anthropic: 1.25× write / 0.1× read).
 */
public interface CacheAwareTokenUsage {

    /** Tokens written to the prompt cache on this call. {@code 0} when none. */
    long cacheCreationInputTokens();

    /** Tokens read from the prompt cache on this call. {@code 0} when none. */
    long cacheReadInputTokens();
}
