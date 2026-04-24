package de.mhus.vance.brain.ai;

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

    public static AiChatOptions defaults() {
        return AiChatOptions.builder().build();
    }
}
