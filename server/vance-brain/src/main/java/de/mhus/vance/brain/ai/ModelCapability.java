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
    PDF;

    /** Case-insensitive lookup; {@link Optional#empty()} on unknown values. */
    public static Optional<ModelCapability> fromString(String s) {
        if (s == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(ModelCapability.valueOf(s.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
