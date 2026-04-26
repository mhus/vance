package de.mhus.vance.brain.ai;

/**
 * Static facts about a provider/model pair — context window and a
 * sensible per-call output cap. Sourced from
 * {@code vance-brain/src/main/resources/ai-models.yaml} and looked up
 * by {@link ModelCatalog}.
 *
 * <p>Used (so far) to drive memory compaction: when the replayed chat
 * history's token estimate exceeds a fraction of {@link
 * #contextWindowTokens()}, the engine compacts older messages into a
 * summary before sending the next request.
 */
public record ModelInfo(
        String provider,
        String modelName,
        int contextWindowTokens,
        int defaultMaxOutputTokens) {

    /** Tokens at which compaction should fire, given a trigger ratio. */
    public int compactionTriggerTokens(double ratio) {
        if (ratio <= 0.0 || ratio > 1.0) {
            throw new IllegalArgumentException(
                    "compaction ratio must be in (0,1]: " + ratio);
        }
        return (int) Math.floor(contextWindowTokens * ratio);
    }
}
