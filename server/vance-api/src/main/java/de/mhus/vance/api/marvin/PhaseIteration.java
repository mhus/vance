package de.mhus.vance.api.marvin;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One LLM-call's worth of phase audit data. Stored in
 * {@code MarvinNodeDocument.phaseHistory} in chronological order
 * for UI replay and debugging; never re-injected into LLM memory.
 *
 * @param phase             which phase this call ran
 * @param iterationIndex    iteration counter within the phase
 *                          (0 for single-shot phases; up to N-1
 *                          for REFLECT/VALIDATE loops)
 * @param outputJson        parsed LLM output, JSON-serialised
 * @param model             "provider:modelName" alias used
 * @param promptTokens      input tokens, if reported by provider
 * @param completionTokens  output tokens, if reported by provider
 * @param timestamp         when the call completed
 */
public record PhaseIteration(
        WorkerPhase phase,
        int iterationIndex,
        String outputJson,
        String model,
        @Nullable Integer promptTokens,
        @Nullable Integer completionTokens,
        Instant timestamp) {}
