package de.mhus.vance.brain.ai;

import java.util.Locale;
import java.util.Optional;

/**
 * Reasoning / "extended thinking" intensity requested from the model.
 * Provider-agnostic surface — each {@link AiModelProvider} maps it to
 * its native parameter:
 *
 * <ul>
 *   <li>OpenAI / LM Studio → {@code reasoning_effort}
 *       ({@code minimal} / {@code low} / {@code medium} / {@code high},
 *       only honored by reasoning models like o1, o3, gpt-5).</li>
 *   <li>Anthropic → {@code thinking: {type:"enabled", budget_tokens: N}},
 *       with {@code budget_tokens} mapped from the level.</li>
 *   <li>Gemini → {@code thinkingConfig.thinkingLevel} on Gemini 2.5+.</li>
 *   <li>Ollama / Ollama Cloud → {@code think: true} when the level is
 *       not {@link #OFF} (boolean, no graduation in the API).</li>
 * </ul>
 *
 * <p>{@link #OFF} is the default — reasoning models are slower / more
 * expensive, so callers must opt in explicitly via recipe param
 * ({@code params.thinking: high}) or directly on
 * {@link AiChatOptions}.
 *
 * <p>{@link #MINIMAL} only has a native counterpart on OpenAI and
 * Gemini. Providers without a "minimal" tier fall back to the closest
 * available value (typically {@link #LOW}); Ollama treats anything
 * non-{@link #OFF} as {@code think: true}.
 */
public enum ThinkingLevel {

    OFF,
    MINIMAL,
    LOW,
    MEDIUM,
    HIGH;

    /**
     * Case-insensitive lookup tolerant of whitespace and the leading
     * {@code "thinking_"} prefix some recipe configs use. Empty /
     * {@code null} → {@link Optional#empty()} so callers can fall back
     * to the option's default.
     */
    public static Optional<ThinkingLevel> fromString(String s) {
        if (s == null) {
            return Optional.empty();
        }
        String normalized = s.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        if (normalized.startsWith("THINKING_")) {
            normalized = normalized.substring("THINKING_".length());
        }
        try {
            return Optional.of(ThinkingLevel.valueOf(normalized));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Lowercase wire form, used by OpenAI's {@code reasoning_effort} parameter. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
