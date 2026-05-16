package de.mhus.vance.brain.ai;

import java.util.List;
import java.util.function.Consumer;
import lombok.Builder;
import lombok.Data;
import org.jspecify.annotations.Nullable;

/**
 * Runtime parameters for a single chat call. Decoupled from
 * {@link AiChatConfig} (which identifies <i>which</i> model to use): options
 * say <i>how</i> it should behave on this invocation.
 *
 * <p>v1 deliberately has no tool / JSON-schema fields — those arrive with
 * Arthur and DeepThink.
 */
@Data
@Builder(toBuilder = true)
public class AiChatOptions {

    /** Sampling temperature, typically 0.0–2.0. */
    @Builder.Default
    private Double temperature = 0.7;

    /** When {@code true}, {@code EngineChatFactory.applySamplingParams}
     *  leaves every sampling field on this options object alone —
     *  the caller has explicitly set what they want and engineParams
     *  should not override. Used by callers (e.g. content-validating
     *  judge) that need a different temperature than the process-level
     *  recipe default. */
    @Builder.Default
    private @Nullable Boolean lockSampling = null;

    /** Hard cap on generated tokens. {@code null} means provider default. */
    private @Nullable Integer maxTokens;

    /**
     * Nucleus-sampling cutoff (0..1). {@code null} → provider default.
     * Honored by every provider that exposes a sampling control
     * (OpenAI, Anthropic, Gemini, Ollama, LM Studio). Recipes set this
     * via {@code params.topP}.
     */
    private @Nullable Double topP;

    /**
     * Top-K sampling cutoff. {@code null} → provider default. Supported
     * by Anthropic, Gemini, and Ollama. OpenAI / LM Studio ignore it
     * silently because the Chat-Completions wire protocol has no
     * matching field. Recipes set this via {@code params.topK}.
     */
    private @Nullable Integer topK;

    /**
     * Sequences that stop generation as soon as the model emits them.
     * Empty / {@code null} → no client-side stop conditions. Honored
     * across providers (OpenAI {@code stop}, Anthropic
     * {@code stop_sequences}, Gemini {@code stopSequences}, Ollama
     * {@code stop}). Recipes set this via {@code params.stopSequences}.
     */
    private @Nullable List<String> stopSequences;

    /**
     * Deterministic-sampling seed. {@code null} → provider default
     * (non-deterministic). Honored by OpenAI, Gemini, and Ollama for
     * replay / QA reproducibility. Anthropic ignores it silently — its
     * API has no equivalent today. Recipes set this via
     * {@code params.seed}.
     */
    private @Nullable Long seed;

    /**
     * Penalty applied to tokens by how often they have appeared
     * (OpenAI's {@code frequency_penalty}, also maps to Ollama's
     * {@code repeat_penalty}). {@code null} → provider default.
     * Anthropic / Gemini ignore it silently. Recipes set this via
     * {@code params.frequencyPenalty}.
     */
    private @Nullable Double frequencyPenalty;

    /**
     * Penalty applied to tokens that already appeared at least once
     * (OpenAI's {@code presence_penalty}). {@code null} → provider
     * default. Providers without an equivalent field ignore it
     * silently. Recipes set this via {@code params.presencePenalty}.
     */
    private @Nullable Double presencePenalty;

    /** Per-call timeout in seconds. */
    @Builder.Default
    private Integer timeoutSeconds = 60;

    /** Prepended as a system message if non-null/blank. */
    private @Nullable String systemMessage;

    /** If {@code true}, the underlying provider logs requests + responses. */
    @Builder.Default
    private Boolean logRequests = false;

    /**
     * Optional callback fired by {@link ResilientStreamingChatModel} on
     * every retry and chain-advance, with a human-readable explanation.
     * Engines wire this to {@code ProgressEmitter.emitStatus(process,
     * StatusTag.PROVIDER, msg)} so the user sees why a turn is taking
     * longer than usual. {@code null} disables resilience feedback.
     */
    private @Nullable Consumer<String> userNotifier;

    /**
     * Optional hook fired by {@link LoggingChatModel} /
     * {@link LoggingStreamingChatModel} after each LLM round-trip.
     * Engines pass a Lambda closing over their {@code process},
     * {@code engineName} and {@code turnId} to persist the raw I/O
     * via {@code LlmTraceService} when the tenant has
     * {@code tracing.llm} enabled. {@code null} disables persistence
     * (log-level trace continues regardless).
     */
    private @Nullable LlmTraceWriter llmTraceWriter;

    /**
     * Where to place the {@code cache_control} marker on the
     * outbound request. Default {@link CacheBoundary#SYSTEM_AND_TOOLS}
     * — the wirtschaftlich sweet spot for most engines. Set to
     * {@link CacheBoundary#NONE} to disable caching for a single call
     * (e.g. debugging cache-stability issues).
     *
     * <p>Providers without a cache concept (Gemini, embedding) ignore
     * this field. The Anthropic adapter is the only one that reads it
     * today.
     */
    @Builder.Default
    private CacheBoundary cacheBoundary = CacheBoundary.SYSTEM_AND_TOOLS;

    /**
     * Requested cache lifetime — 5 minutes by default. {@link
     * CacheTtl#LONG_1H} switches the adapter into the
     * {@code extended-cache-ttl-2025-04-11} beta and costs ~2× write
     * up-front. Ignored when {@link #cacheBoundary} is
     * {@link CacheBoundary#NONE}.
     */
    @Builder.Default
    private CacheTtl cacheTtl = CacheTtl.DEFAULT_5MIN;

    /**
     * Reasoning / "extended thinking" intensity. {@link ThinkingLevel#OFF}
     * by default — reasoning models cost more and respond slower, so
     * callers opt in explicitly. Recipe param {@code params.thinking}
     * (e.g. {@code high}) is parsed by
     * {@link EngineChatFactory} and lands here. Each provider maps the
     * level to its native parameter; providers whose models don't
     * support reasoning surface a clean error from the API rather than
     * silently ignoring the field.
     */
    @Builder.Default
    private ThinkingLevel thinkingLevel = ThinkingLevel.OFF;

    /**
     * Scope hints used by {@link ModelCatalog} to apply cascade
     * overrides (project → _vance → bundled) on
     * {@code ai-models.yaml}. {@code null} on either field means
     * "no scope" → bundled-only catalog read. Engines populate both
     * from the spawning {@code ThinkProcessDocument} via
     * {@link EngineChatFactory}, so providers that consult
     * {@link ModelCatalog} for capability/size info honour any
     * tenant- or project-level model overrides automatically.
     */
    private @Nullable String tenantId;

    private @Nullable String projectId;

    public static AiChatOptions defaults() {
        return AiChatOptions.builder().build();
    }
}
