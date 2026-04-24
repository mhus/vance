/**
 * Anthropic provider implementation for {@link de.mhus.vance.brain.ai.AiModelProvider}.
 *
 * <p>Built on {@code langchain4j-anthropic}. Each call constructs a matched
 * pair of {@code AnthropicChatModel} (sync) and
 * {@code AnthropicStreamingChatModel} (streaming) — so callers can reach for
 * either directly via {@link de.mhus.vance.brain.ai.AiChat#chatModel()} /
 * {@link de.mhus.vance.brain.ai.AiChat#streamingChatModel()} without
 * triggering another build.
 */
@NullMarked
package de.mhus.vance.brain.ai.anthropic;

import org.jspecify.annotations.NullMarked;
