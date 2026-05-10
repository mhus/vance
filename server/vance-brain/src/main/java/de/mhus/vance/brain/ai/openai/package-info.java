/**
 * OpenAI provider implementation for
 * {@link de.mhus.vance.brain.ai.AiModelProvider}.
 *
 * <p>Built on {@code langchain4j-open-ai}. Each call constructs a matched
 * {@code OpenAiChatModel} (sync) and {@code OpenAiStreamingChatModel}
 * (streaming) pair and hands them to a shared
 * {@link de.mhus.vance.brain.ai.StandardAiChat}.
 *
 * <p>The base URL defaults to OpenAI's public endpoint and can be
 * overridden via {@code vance.ai.openai.base-url} — useful for OpenAI-
 * compatible gateways (Azure-OpenAI proxy, on-prem reverse proxies). LM
 * Studio uses its own provider ({@link de.mhus.vance.brain.ai.lmstudio})
 * with a different default base URL.
 */
@NullMarked
package de.mhus.vance.brain.ai.openai;

import org.jspecify.annotations.NullMarked;
