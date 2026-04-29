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

    public static AiChatOptions defaults() {
        return AiChatOptions.builder().build();
    }
}
