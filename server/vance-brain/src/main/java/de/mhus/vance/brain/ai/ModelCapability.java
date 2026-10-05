package de.mhus.vance.brain.ai;

import java.util.Locale;
import java.util.Optional;

/**
 * Optional capabilities of a provider/model pair, sourced from
 * {@code ai-models.yaml}'s {@code capabilities: [...]} list.
 *
 * <p>Drives the attachment dispatch in {@code StandardAiChat}:
 *
 * <ul>
 *   <li>{@link #VISION} — model accepts image content blocks.</li>
 *   <li>{@link #PDF} — model accepts PDF content blocks natively
 *       (Anthropic's vision pipeline, Gemini's document mode,
 *       OpenAI's {@code input_file}). Models without this flag get
 *       a PDFBox text-extract fallback applied before the call.</li>
 *   <li>{@link #THINKING} — model accepts an explicit reasoning /
 *       extended-thinking control on the request (Anthropic's
 *       {@code thinking={type:enabled,…}}, Gemini 2.5's
 *       {@code thinkingConfig}, OpenAI o-series reasoning_effort).
 *       Without this flag a recipe's {@code thinking: high} request is
 *       silently downgraded to off by the provider — the API would
 *       otherwise 400 the call. Recipe authors should not have to
 *       know which model/SDK combo currently honors thinking; the
 *       capability list is the single point of truth.</li>
 * </ul>
 *
 * <p>Models that aren't listed in {@code ai-models.yaml} fall back
 * to <b>no capabilities</b> (pessimistic default — see
 * {@code ModelCatalog} javadoc). That's deliberate: silently sending
 * a binary blob to a non-vision model is a 30 s timeout waiting to
 * happen.
 */
public enum ModelCapability {
    VISION,
    PDF,
    THINKING,
    /**
     * Model accepts {@code {role:"system"}} messages inside the conversation
     * without that invalidating the prompt cache (Anthropic: Opus 4.8/5/5.5,
     * Sonnet 5.5, Fable/Mythos 5 and 5.1 — <b>not</b> Sonnet 5, Haiku or the
     * 4.x families). The Anthropic mapper needs this to move the dynamic
     * system blocks out of the top-level {@code system} array, which is what
     * makes the history cache breakpoint (see {@link CacheBoundary}) hit at
     * all. Unknown models are treated as <i>without</i> the capability:
     * sending a {@code role:"system"} message to a model that rejects it is
     * a hard 400, and this is a wire-shaping decision the catalog owns.
     *
     * <p>Declared per model in {@code ai-models.yaml} — never guessed from
     * the model name, because a wrong guess is an API error, not a cache
     * miss.
     */
    MID_CONVERSATION_SYSTEM;

    /** Case-insensitive lookup; {@link Optional#empty()} on unknown values. */
    public static Optional<ModelCapability> fromString(String s) {
        if (s == null) {
            return Optional.empty();
        }
        try {
            // Kebab-case capability names (mid-conversation-system) map onto
            // the enum constants the same way kebab-case settings map onto
            // their property names.
            return Optional.of(ModelCapability.valueOf(
                    s.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_')));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
