package de.mhus.vance.brain.ai;

import java.util.Set;

/**
 * Static facts about a provider/model pair — context window, a
 * sensible per-call output cap, the set of optional input
 * capabilities (vision, PDF, thinking, …), and a per-call timeout
 * budget. Sourced from
 * {@code vance-brain/src/main/resources/ai-models.yaml} and looked up
 * by {@link ModelCatalog}.
 *
 * <p>Used to drive memory compaction (token-budget gate against
 * {@link #contextWindowTokens()}), attachment dispatch in
 * {@code StandardAiChat} (vision/PDF gate against
 * {@link #capabilities()}), per-provider thinking-config gating
 * (see {@link ModelCapability#THINKING}), and the per-call HTTP
 * timeout each provider applies (see
 * {@link #effectiveTimeoutSeconds(Integer)}).
 */
public record ModelInfo(
        String provider,
        String modelName,
        int contextWindowTokens,
        int defaultMaxOutputTokens,
        ModelSize size,
        Set<ModelCapability> capabilities,
        int timeoutSeconds,
        int actionLoopCorrections) {

    /**
     * Conservative per-call timeout used when neither the catalog
     * entry nor the model's record carries one. 60s covers a typical
     * Anthropic/Gemini-Flash response; slower models (Pro under
     * reasoning load, local LLMs) should override via {@code
     * timeoutSeconds:} in {@code ai-models.yaml}.
     */
    public static final int DEFAULT_TIMEOUT_SECONDS = 60;

    /**
     * Default budget for action-loop "free text without tool call"
     * corrections. The action loop re-prompts the LLM up to this many
     * times before falling back to the best free-text it captured.
     * Two is enough for most models; Gemini 2.5 Pro occasionally
     * emits empty {@code STOP} after long tool-call sequences and
     * benefits from a higher budget — bump it via the
     * {@code actionLoopCorrections} field in {@code ai-models.yaml}.
     */
    public static final int DEFAULT_ACTION_LOOP_CORRECTIONS = 2;

    public ModelInfo {
        // Defensive copy + immutability so callers can hand the record
        // around without worrying about Set mutation.
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        if (timeoutSeconds <= 0) {
            timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        }
        if (actionLoopCorrections <= 0) {
            actionLoopCorrections = DEFAULT_ACTION_LOOP_CORRECTIONS;
        }
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

    /**
     * Resolve the per-call timeout that providers should apply.
     * Caller-set {@link AiChatOptions#getTimeoutSeconds()} wins when
     * not {@code null} (escape hatch for tests / hooks that need a
     * specific budget); otherwise the model-level value from this
     * record. Both layers default to {@link #DEFAULT_TIMEOUT_SECONDS}
     * when nothing else is configured.
     */
    public int effectiveTimeoutSeconds(@org.jspecify.annotations.Nullable Integer callerOverride) {
        if (callerOverride != null && callerOverride > 0) {
            return callerOverride;
        }
        return timeoutSeconds;
    }
}
