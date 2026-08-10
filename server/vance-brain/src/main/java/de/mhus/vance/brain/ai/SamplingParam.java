package de.mhus.vance.brain.ai;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A per-call sampling knob on {@link AiChatOptions} that a model may
 * refuse to accept.
 *
 * <p>OpenAI's reasoning models (o-series, gpt-5 and newer) run with
 * fixed decoding and reject the classic sampling fields outright:
 *
 * <pre>{@code
 * Unsupported value: 'temperature' does not support 0.2 with this model.
 *                    Only the default (1) value is supported.
 * Unsupported parameter: 'top_p' is not supported with this model.
 * Unsupported parameter: 'presence_penalty' is not supported with this model.
 * Unsupported parameter: 'stop' is not supported with this model.
 * }</pre>
 *
 * <p>Each is a hard HTTP 400, so a single recipe default (and
 * {@link AiChatOptions} defaults {@code temperature} for <i>every</i>
 * call) is enough to make the model unusable. Which knobs a model
 * refuses is a catalog fact — see {@code ModelInfo.unsupportedParams()}
 * — and {@code AbstractChatProvider} clears them before the provider
 * builds its request, so the model runs with its own defaults instead
 * of failing.
 *
 * <p>Names parse in both spellings: the {@link AiChatOptions} field
 * name ({@code topP}) and the wire name ({@code top_p}), so an operator
 * can paste the offending {@code "param"} straight out of the provider's
 * error into the model YAML.
 */
public enum SamplingParam {

    TEMPERATURE("temperature", "temperature"),
    TOP_P("topP", "top_p"),
    TOP_K("topK", "top_k"),
    FREQUENCY_PENALTY("frequencyPenalty", "frequency_penalty"),
    PRESENCE_PENALTY("presencePenalty", "presence_penalty"),
    SEED("seed", "seed"),
    STOP_SEQUENCES("stopSequences", "stop");

    private final String fieldName;
    private final String wireName;

    SamplingParam(String fieldName, String wireName) {
        this.fieldName = fieldName;
        this.wireName = wireName;
    }

    /** The {@link AiChatOptions} property name. */
    public String fieldName() {
        return fieldName;
    }

    /** The JSON field name on an OpenAI-shaped request. */
    public String wireName() {
        return wireName;
    }

    /**
     * Parse a YAML entry in either spelling (case-insensitive,
     * {@code -}/{@code _} interchangeable). {@code null} for unknown
     * input so the caller can log the typo instead of silently
     * dropping a whole model entry.
     */
    public static @Nullable SamplingParam fromYaml(@Nullable String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        for (SamplingParam p : values()) {
            if (normalized.equals(p.fieldName.toLowerCase(Locale.ROOT))
                    || normalized.equals(p.wireName.replace("_", ""))) {
                return p;
            }
        }
        return null;
    }
}
