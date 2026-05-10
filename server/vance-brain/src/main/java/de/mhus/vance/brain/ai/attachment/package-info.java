/**
 * Server-side attachment resolution for multimodal LLM calls.
 *
 * <p>Engines pass {@link de.mhus.vance.api.attachment.AttachmentRef}
 * pointers from the inbound chat message; the
 * {@link de.mhus.vance.brain.ai.attachment.AttachmentResolver} loads
 * the matching {@code DocumentDocument}, validates project scope,
 * applies the configured size limits, and returns a
 * {@link de.mhus.vance.brain.ai.attachment.ResolvedAttachment}
 * that {@code StandardAiChat} feeds into the langchain4j
 * {@code Content}-block list of the outgoing user message.
 *
 * <p>Soft limits live as Spring properties
 * ({@code vance.ai.attachment.max-bytes-per-file},
 * {@code vance.ai.attachment.max-bytes-per-request}); the providers
 * themselves enforce the API-level hard limits.
 */
@NullMarked
package de.mhus.vance.brain.ai.attachment;

import org.jspecify.annotations.NullMarked;
