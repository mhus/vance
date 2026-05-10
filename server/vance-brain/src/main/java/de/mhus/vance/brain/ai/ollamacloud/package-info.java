/**
 * Ollama Cloud (ollama.com) provider implementation for
 * {@link de.mhus.vance.brain.ai.AiModelProvider}.
 *
 * <p>Built on {@code langchain4j-ollama} — the protocol is identical to
 * self-hosted Ollama, but the endpoint defaults to
 * {@code https://ollama.com} and authenticates via
 * {@code Authorization: Bearer <apiKey>}, injected as a custom header
 * since {@link dev.langchain4j.model.ollama.OllamaChatModel} doesn't
 * have a first-class {@code apiKey} field.
 *
 * <p>Self-hosted Ollama uses {@link de.mhus.vance.brain.ai.ollama} —
 * separate provider with separate base-URL setting and no auth header.
 */
@NullMarked
package de.mhus.vance.brain.ai.ollamacloud;

import org.jspecify.annotations.NullMarked;
