package de.mhus.vance.brain.ai.audio;

import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Static facts about an audio-understanding provider/model pair — the
 * {@code kind: au} catalog entries that power {@code audio_analyze}.
 *
 * <p>Distinct from {@link SttModelInfo} on purpose: transcription maps
 * audio to spoken text, analysis describes the audio itself (genre,
 * instrumentation, tempo, mood, structure, quality). The wire shape
 * differs accordingly (chat completions with an {@code input_audio}
 * content block vs. the {@code /audio/transcriptions} endpoint), so
 * the two kinds never share a lookup pool.
 *
 * <p>There is no {@code supportedLanguages} here: input is <i>any</i>
 * audio — speech, music, soundscapes — and the output language is a
 * prompt instruction, not a model gate. Cost is declared per second of
 * input audio ({@code costPerSecond}); the call books the
 * vendor-reported {@code usage.cost} whenever the provider sends one.
 *
 * @param provider           protocol wire-name
 * @param modelName          provider-specific model id
 * @param supportedFormats   accepted input formats
 *                           ({@code wav}, {@code mp3}, …) — the only
 *                           real format gate: OpenRouter accepts
 *                           arbitrary format strings silently
 * @param maxDurationSeconds per-call audio cap; our own cost/payload
 *                           limit, not a provider contract
 * @param costPerSecond      USD per second of input audio, or
 *                           {@code null} when unpriced
 * @param timeoutSeconds     per-call HTTP timeout
 */
public record AuModelInfo(
        String provider,
        String modelName,
        Set<String> supportedFormats,
        int maxDurationSeconds,
        @Nullable Double costPerSecond,
        int timeoutSeconds) {

    /** Per-call HTTP timeout used when the catalog entry doesn't carry one. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 300;

    /** Default per-call audio cap when the catalog entry doesn't set one. */
    public static final int DEFAULT_MAX_DURATION_SECONDS = 1800;

    /** Formats assumed when the catalog entry omits the list. */
    public static final Set<String> DEFAULT_FORMATS = Set.of("wav", "mp3", "m4a", "ogg", "flac");

    public AuModelInfo {
        supportedFormats =
                supportedFormats == null || supportedFormats.isEmpty() ? DEFAULT_FORMATS : Set.copyOf(supportedFormats);
        if (maxDurationSeconds <= 0) {
            maxDurationSeconds = DEFAULT_MAX_DURATION_SECONDS;
        }
        if (timeoutSeconds <= 0) {
            timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        }
    }

    /** Whether {@code format} is an accepted input format. */
    public boolean supportsFormat(String format) {
        return supportedFormats.contains(format.toLowerCase(Locale.ROOT));
    }

    /** Cost in USD for {@code seconds} of audio, or {@code null} when unpriced. */
    @Nullable
    public Double costForSeconds(double seconds) {
        return costPerSecond == null ? null : costPerSecond * seconds;
    }
}
