/**
 * LM Studio provider implementation for
 * {@link de.mhus.vance.brain.ai.AiModelProvider}.
 *
 * <p>LM Studio exposes an OpenAI-compatible REST endpoint (default
 * {@code http://localhost:1234/v1}). Built on the same
 * {@code langchain4j-open-ai} models as
 * {@link de.mhus.vance.brain.ai.openai.OpenAiProvider} but with a
 * separate Spring bean so users can address it by its own wire-name in
 * settings/recipes ({@code lmstudio}) and override the base URL via
 * {@code vance.ai.lmstudio.base-url} independently of the OpenAI
 * setting.
 *
 * <p>Local LM Studio doesn't authenticate. The {@code apiKey} on
 * {@link de.mhus.vance.brain.ai.AiChatConfig} is required by the record
 * contract but the LM Studio server ignores any value; operators put a
 * placeholder string in {@code ai.provider.lmstudio.apiKey}.
 */
@NullMarked
package de.mhus.vance.brain.ai.lmstudio;

import org.jspecify.annotations.NullMarked;
