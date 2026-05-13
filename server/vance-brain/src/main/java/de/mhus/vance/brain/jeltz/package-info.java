/**
 * Jeltz — single-shot structured-output {@link de.mhus.vance.brain.thinkengine.ThinkEngine}.
 *
 * <p>Takes a {@code prompt} and a {@code schema} (JSON Schema subset) via
 * engine params, calls the configured LLM, validates the reply against
 * the schema, retries on violation up to a configurable limit, and
 * persists the result wrapped with {@code success} / {@code attempts} /
 * {@code data} (or {@code error} / {@code message} / {@code lastInvalid})
 * as the final assistant message. The process is always closed with
 * {@code CloseReason.DONE} after start, regardless of validation outcome.
 *
 * <p>Spec: {@code specification/jeltz-engine.md}.
 */
@NullMarked
package de.mhus.vance.brain.jeltz;

import org.jspecify.annotations.NullMarked;
