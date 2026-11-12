/**
 * The {@code iris} provider: the brain's side of the Iris model gateway.
 *
 * <p>Iris speaks the OpenAI chat wire, so chat and embeddings are built exactly
 * like {@link de.mhus.vance.brain.ai.openai.OpenAiProvider} builds them. What
 * differs is how Iris says "no": it answers with a machine-readable error code
 * ({@code quota_exhausted}, {@code window_exhausted}, {@code request_limit}, {@code model_not_allowed},
 * …) that a plain OpenAI adapter would treat by HTTP status alone. That is
 * wrong for a 429 that means "your five-hour window is full for hours".
 */
@NullMarked
package de.mhus.vance.brain.ai.iris;

import org.jspecify.annotations.NullMarked;
