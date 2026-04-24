/**
 * Google Gemini provider implementation for
 * {@link de.mhus.vance.brain.ai.AiModelProvider}.
 *
 * <p>Built on {@code langchain4j-google-ai-gemini}. Same pattern as the
 * Anthropic provider: each call constructs a matched sync + streaming model
 * pair and hands them to a shared
 * {@link de.mhus.vance.brain.ai.StandardAiChat}.
 */
@NullMarked
package de.mhus.vance.brain.ai.gemini;

import org.jspecify.annotations.NullMarked;
