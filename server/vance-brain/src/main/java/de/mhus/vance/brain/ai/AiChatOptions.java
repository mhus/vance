package de.mhus.vance.brain.ai;

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
@Builder
public class AiChatOptions {

    /** Sampling temperature, typically 0.0–2.0. */
    @Builder.Default
    private Double temperature = 0.7;

    /** Hard cap on generated tokens. {@code null} means provider default. */
    private @Nullable Integer maxTokens;

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

    public static AiChatOptions defaults() {
        return AiChatOptions.builder().build();
    }
}
