package de.mhus.vance.brain.ai;

import java.util.Set;
import org.jspecify.annotations.Nullable;

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
 * (see {@link ModelCapability#THINKING}), the per-call HTTP
 * timeout each provider applies (see
 * {@link #effectiveTimeoutSeconds(Integer)}), and the wire field that
 * carries the output cap on OpenAI-shaped requests (see
 * {@link OutputTokenParam}), the sampling knobs the model refuses
 * (see {@link SamplingParam}), and how "no reasoning" has to be
 * spelled on the wire (see {@link #reasoningEffortWhenOff()}).
 */
public record ModelInfo(
        String provider,
        String modelName,
        int contextWindowTokens,
        int defaultMaxOutputTokens,
        ModelSize size,
        Set<ModelCapability> capabilities,
        int timeoutSeconds,
        int actionLoopCorrections,
        boolean stripThinkTags,
        @Nullable String messageParser,
        @Nullable Pricing pricing,
        OutputTokenParam outputTokenParam,
        Set<SamplingParam> unsupportedParams,
        @Nullable String reasoningEffortWhenOff,
        @Nullable Integer maxTools) {

    /*
     * maxTools — hard cap the endpoint enforces on the `tools` array.
     * Not a model property: it is request validation, applied before the
     * model sees anything. An OpenAI-wire gateway answers an oversized
     * manifest with
     *
     *   "Invalid 'tools': array too long. Expected an array with maximum
     *    length 128, but got an array with length 163 instead."
     *
     * — HTTP 400, no tokens spent, and every fallback entry with the same
     * limit fails identically. Since it belongs to the endpoint, the
     * default lives in the provider sidecar (`_provider.yaml`) and a
     * per-model entry only overrides it.
     *
     * null = no known limit (Anthropic today); the tool-surface budget is
     * then a no-op and the token budget is the only ceiling. Never
     * populated by auto-discovery: no listing API reports it, so it is
     * manual-layer metadata like pricing.
     */

    /**
     * Legacy constructor for the many call sites that predate the tool
     * cap — leaves {@code maxTools} unset, i.e. "no known limit". New
     * code should pass the field explicitly.
     */
    public ModelInfo(
            String provider,
            String modelName,
            int contextWindowTokens,
            int defaultMaxOutputTokens,
            ModelSize size,
            Set<ModelCapability> capabilities,
            int timeoutSeconds,
            int actionLoopCorrections,
            boolean stripThinkTags,
            @Nullable String messageParser,
            @Nullable Pricing pricing,
            OutputTokenParam outputTokenParam,
            Set<SamplingParam> unsupportedParams,
            @Nullable String reasoningEffortWhenOff) {
        this(provider, modelName, contextWindowTokens, defaultMaxOutputTokens, size,
                capabilities, timeoutSeconds, actionLoopCorrections, stripThinkTags,
                messageParser, pricing, outputTokenParam, unsupportedParams,
                reasoningEffortWhenOff, /*maxTools*/ null);
    }

    /*
     * reasoningEffortWhenOff — the wire value to send for
     * `reasoning_effort` when Vance wants NO reasoning. Normally null:
     * omitting the field is how you say "don't reason".
     *
     * Reasoning-native models break that assumption. gpt-5.6-sol
     * reasons by default and refuses the combination with function
     * tools on /v1/chat/completions (verified 2026-08-10):
     *
     *   "Function tools with reasoning_effort are not supported for
     *    gpt-5.6-sol in /v1/chat/completions. To use function tools,
     *    use /v1/responses or set reasoning_effort to 'none'."
     *
     * Since every engine turn carries a tool manifest, such a model is
     * unusable until the request says `reasoning_effort: "none"`
     * out loud. That is a per-model fact, not a provider default —
     * older o-series models don't know the value at all.
     */

    /**
     * Per-million-token rates that drive cost accounting. Pulled from
     * the {@code pricing:} block of an {@code ai-models.yaml} entry,
     * snapshotted into every {@code LlmUsageDocument} write so a later
     * rate change doesn't rewrite history.
     *
     * <p>{@code cacheReadPerMTok} / {@code cacheWritePerMTok} are
     * optional — only providers with prompt-caching support (Anthropic
     * today, Gemini partial) populate them. Missing values mean "no
     * cache pricing" and cost stays zero for that bucket.
     */
    public record Pricing(
            String currency,
            double inputPerMTok,
            double outputPerMTok,
            @Nullable Double cacheReadPerMTok,
            @Nullable Double cacheWritePerMTok) {

        public Pricing {
            if (currency == null || currency.isBlank()) {
                throw new IllegalArgumentException("Pricing.currency is required");
            }
            currency = currency.trim().toUpperCase(java.util.Locale.ROOT);
        }
    }

    /**
     * Conservative per-call timeout used when neither the catalog
     * entry nor the model's record carries one. 60s covers a typical
     * Anthropic/Gemini-Flash response; slower models (Pro under
     * reasoning load, local LLMs) should override via {@code
     * timeoutSeconds:} in {@code ai-models.yaml}.
     */
    public static final int DEFAULT_TIMEOUT_SECONDS = 60;

    /**
     * Floor for the <b>streaming</b> total-request timeout. A streamed
     * response legitimately runs far longer than a single sync JSON
     * response — it emits tokens over time — so applying the sync
     * {@link #effectiveTimeoutSeconds} budget to a stream cuts off
     * healthy long generations at the sync limit. The 2026-07-29
     * {@code deepseek-v4-pro} incident was exactly this: a big-manifest
     * chat turn hit the 60s sync cap mid-stream and got abandoned to
     * the fallback model. Streaming therefore gets a generous floor
     * while a truly hung connection stays bounded.
     */
    public static final int DEFAULT_STREAM_TIMEOUT_SECONDS = 300;

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
        // 0 / negative in YAML means the same as absent: no known cap.
        // Normalising here keeps every reader from re-checking the sign.
        if (maxTools != null && maxTools <= 0) {
            maxTools = null;
        }
        if (outputTokenParam == null) {
            outputTokenParam = OutputTokenParam.MAX_TOKENS;
        }
        unsupportedParams = unsupportedParams == null
                ? Set.of()
                : Set.copyOf(unsupportedParams);
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

    /**
     * Resolve the per-call timeout providers should apply to the
     * <b>streaming</b> model. Never shorter than
     * {@link #effectiveTimeoutSeconds(Integer)} and never below
     * {@link #DEFAULT_STREAM_TIMEOUT_SECONDS} — a slow model that
     * streams for minutes is not cut off at the sync budget, while a
     * caller/recipe that pins an even larger budget still wins.
     */
    public int effectiveStreamTimeoutSeconds(
            @org.jspecify.annotations.Nullable Integer callerOverride) {
        return Math.max(
                effectiveTimeoutSeconds(callerOverride), DEFAULT_STREAM_TIMEOUT_SECONDS);
    }

    /**
     * Per-token slope for context-scaled streaming timeouts. ~4ms per
     * estimated input token — a slow provider needs proportionally more
     * time to ingest a large prompt before the first streamed token.
     */
    private static final double STREAM_TIMEOUT_MS_PER_TOKEN = 4.0;

    /** Hard ceiling for the scaled streaming timeout. */
    public static final int MAX_STREAM_TIMEOUT_SECONDS = 900;

    /**
     * Context-scaled variant of {@link #effectiveStreamTimeoutSeconds}:
     * a large request legitimately needs a longer budget than a small
     * one, so the streaming timeout grows with the estimated input-token
     * count. See {@code planning/completion-guard.md} discussion / the
     * timeout-scaling design.
     *
     * <p>Semantics:
     * <ul>
     *   <li>an explicit {@code callerOverride} still wins (floored at
     *       {@link #DEFAULT_STREAM_TIMEOUT_SECONDS} as before);</li>
     *   <li>no estimate ({@code null}/{@code <= 0}) → the unscaled
     *       {@link #effectiveStreamTimeoutSeconds} — fully
     *       backward-compatible;</li>
     *   <li>otherwise {@code clamp(base + perToken·est,
     *       DEFAULT_STREAM_TIMEOUT_SECONDS, MAX_STREAM_TIMEOUT_SECONDS)}.
     *       The lower clamp is the existing 300s floor, so scaling can
     *       only ever <em>lengthen</em> the budget — never a regression.</li>
     * </ul>
     */
    public int scaledStreamTimeoutSeconds(
            @org.jspecify.annotations.Nullable Integer callerOverride,
            @org.jspecify.annotations.Nullable Integer estInputTokens) {
        if (callerOverride != null && callerOverride > 0) {
            return effectiveStreamTimeoutSeconds(callerOverride);
        }
        if (estInputTokens == null || estInputTokens <= 0) {
            return effectiveStreamTimeoutSeconds(null);
        }
        long scaled = timeoutSeconds
                + Math.round(estInputTokens * STREAM_TIMEOUT_MS_PER_TOKEN / 1000.0);
        long clamped = Math.max(DEFAULT_STREAM_TIMEOUT_SECONDS,
                Math.min(MAX_STREAM_TIMEOUT_SECONDS, scaled));
        return (int) clamped;
    }

    /**
     * Resolve the per-call output-token cap that providers should send
     * as {@code max_tokens}. A caller-set value wins when not
     * {@code null}; otherwise the model-level {@link
     * #defaultMaxOutputTokens()} from the catalog applies. Without this
     * fallback the wire request omits {@code max_tokens} entirely and
     * OpenAI-wire gateways reserve the whole remaining context window as
     * output — which overflows the context limit on large prompts.
     */
    public int effectiveMaxOutputTokens(@org.jspecify.annotations.Nullable Integer callerOverride) {
        if (callerOverride != null && callerOverride > 0) {
            return callerOverride;
        }
        return defaultMaxOutputTokens;
    }
}
