package de.mhus.vance.brain.ai;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Which wire field carries the output-token cap on an OpenAI-shaped
 * chat request.
 *
 * <p>OpenAI's reasoning models (o-series, gpt-5 and newer) rejected
 * the historic {@code max_tokens} field outright:
 *
 * <pre>{@code
 * Unsupported parameter: 'max_tokens' is not supported with this
 * model. Use 'max_completion_tokens' instead.
 * }</pre>
 *
 * <p>The replacement field is <b>not</b> universally available: most
 * OpenAI-compatible gateways (LM Studio, Ollama, cortecs/GLM,
 * DeepSeek) still speak the older dialect only. So this is a per-model
 * fact from the catalog, not a global switch — {@link #MAX_TOKENS}
 * stays the default and models that need the newer field say so, either
 * in their own YAML ({@code outputTokenParam: max_completion_tokens})
 * or through a family pattern in {@code model-quirks.yaml}.
 */
public enum OutputTokenParam {

    /** Historic field, understood by every OpenAI-wire endpoint. */
    MAX_TOKENS("max_tokens"),

    /** Reasoning-model field (o-series, gpt-5+). */
    MAX_COMPLETION_TOKENS("max_completion_tokens");

    private final String wireName;

    OutputTokenParam(String wireName) {
        this.wireName = wireName;
    }

    /** The JSON field name as it appears on the request. */
    public String wireName() {
        return wireName;
    }

    /**
     * Parse a YAML value ({@code max_tokens} / {@code maxTokens} /
     * {@code MAX_COMPLETION_TOKENS} …). Returns {@code null} for
     * {@code null}, blank, or unrecognised input so callers can decide
     * between "not configured" and "typo" and log accordingly.
     */
    public static @Nullable OutputTokenParam fromYaml(@Nullable String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace("-", "_");
        return switch (normalized) {
            case "max_tokens", "maxtokens" -> MAX_TOKENS;
            case "max_completion_tokens", "maxcompletiontokens" -> MAX_COMPLETION_TOKENS;
            default -> null;
        };
    }
}
