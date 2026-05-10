package de.mhus.vance.brain.ai;

import java.util.Set;

/**
 * Static facts about a provider/model pair — context window, a
 * sensible per-call output cap, and the set of optional input
 * capabilities (vision, PDF, …). Sourced from
 * {@code vance-brain/src/main/resources/ai-models.yaml} and looked up
 * by {@link ModelCatalog}.
 *
 * <p>Used to drive memory compaction (token-budget gate against
 * {@link #contextWindowTokens()}) and the attachment dispatch in
 * {@code StandardAiChat} (vision/PDF gate against
 * {@link #capabilities()}).
 */
public record ModelInfo(
        String provider,
        String modelName,
        int contextWindowTokens,
        int defaultMaxOutputTokens,
        ModelSize size,
        Set<ModelCapability> capabilities) {

    public ModelInfo {
        // Defensive copy + immutability so callers can hand the record
        // around without worrying about Set mutation.
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }

    /** Tokens at which compaction should fire, given a trigger ratio. */
    public int compactionTriggerTokens(double ratio) {
        if (ratio <= 0.0 || ratio > 1.0) {
            throw new IllegalArgumentException(
                    "compaction ratio must be in (0,1]: " + ratio);
        }
        return (int) Math.floor(contextWindowTokens * ratio);
    }

    public boolean supports(ModelCapability capability) {
        return capabilities.contains(capability);
    }
}
