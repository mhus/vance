/**
 * Ollama provider implementation for
 * {@link de.mhus.vance.brain.ai.AiModelProvider}.
 *
 * <p>Built on {@code langchain4j-ollama} for self-hosted Ollama servers.
 * The base URL defaults to {@code http://localhost:11434} and is
 * overridable via {@code vance.ai.ollama.base-url}. Local Ollama
 * doesn't authenticate — the {@code apiKey} carried by
 * {@link de.mhus.vance.brain.ai.AiChatConfig} is treated as a placeholder
 * and not forwarded to the model.
 *
 * <p>For the hosted ollama.com endpoint use
 * {@link de.mhus.vance.brain.ai.ollamacloud.OllamaCloudProvider} —
 * same protocol but with bearer auth.
 */
@NullMarked
package de.mhus.vance.brain.ai.ollama;

import org.jspecify.annotations.NullMarked;
