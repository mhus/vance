package de.mhus.vance.brain.ai.light;

import java.util.Map;

/**
 * Single-shot LLM-call helper with a Recipe as config profile. See
 * {@code specification/light-llm-service.md}.
 *
 * <p>Two entry points:
 *
 * <ul>
 *   <li>{@link #call(LightLlmRequest)} — raw text reply, no schema
 *       validation. Use when the caller will post-process the text
 *       itself (title, summary, classification with free-text label).</li>
 *   <li>{@link #callForJson(LightLlmRequest)} — Jeltz-style schema
 *       validation loop. The LLM is asked to reply with a JSON object
 *       conforming to {@link LightLlmRequest#getSchema()}; on parse
 *       failure or schema violation, the service appends a correction
 *       message and retries (up to {@code maxAttempts}). Throws
 *       {@link SchemaValidationException} when the budget is exhausted.</li>
 * </ul>
 *
 * <p>Recipes consumed here must be marked {@code internal: true} —
 * the service rejects others. This keeps recipes that are config
 * profiles (only ever invoked via {@code LightLlmService}) distinct
 * from recipes that can be spawned as workers.
 */
public interface LightLlmService {

    /** Raw single-shot call. Returns the LLM's reply text verbatim. */
    String call(LightLlmRequest request);

    /**
     * Schema-validated single-shot call. Returns the parsed JSON
     * object (as {@code Map<String, Object>}).
     *
     * @throws SchemaValidationException after {@code maxAttempts}
     *     consecutive failures.
     * @throws LightLlmException for non-schema failures (recipe not
     *     found, LLM provider exhausted, etc.).
     */
    Map<String, Object> callForJson(LightLlmRequest request);
}
