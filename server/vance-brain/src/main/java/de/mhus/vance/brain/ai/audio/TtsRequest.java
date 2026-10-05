package de.mhus.vance.brain.ai.audio;

import org.jspecify.annotations.Nullable;

/**
 * Input for one text-to-speech call — already validated and
 * language-resolved by the Hotblack service.
 *
 * @param text     the text to speak (markdown already stripped by the
 *                 caller — TTS would read the markup aloud)
 * @param language ISO-639-1 language code, or {@code null} for
 *                 provider auto-detection
 * @param voice    provider voice id, or {@code null} for the model's
 *                 default voice
 * @param format   requested output format ({@code "mp3"} or
 *                 {@code "wav"}); providers downshift to their nearest
 *                 supported wire format and wrap accordingly
 * @param speed    playback multiplier, or {@code null} for the
 *                 provider default
 */
public record TtsRequest(
        String text,
        @Nullable String language,
        @Nullable String voice,
        String format,
        @Nullable Double speed) {

    public TtsRequest {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text is blank");
        }
        if (format == null || format.isBlank()) {
            throw new IllegalArgumentException("format is blank");
        }
    }
}
