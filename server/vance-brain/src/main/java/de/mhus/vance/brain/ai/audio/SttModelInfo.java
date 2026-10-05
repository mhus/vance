package de.mhus.vance.brain.ai.audio;

import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Static facts about a transcription provider/model pair — accepted
 * input formats, audio-duration cap, languages, and cost.
 *
 * <p>Sourced from model documents with {@code kind: stt} and looked up
 * via {@code ModelCatalog.lookupStt}. Cost is declared per second of
 * input audio ({@code costPerSecond}); token-priced models leave both
 * fields {@code null} and the call books the vendor-reported
 * {@code usage.cost} instead.
 *
 * @param provider            protocol wire-name
 * @param modelName           provider-specific model id
 * @param supportedFormats    accepted input formats
 *                            ({@code wav}, {@code mp3}, {@code m4a}, …)
 * @param maxDurationSeconds  per-call audio cap (0 = unknown/default)
 * @param supportedLanguages  ISO-639-1 codes; empty = auto-detect /
 *                            any language
 * @param costPerSecond       USD per second of input audio
 * @param costPerMinute       USD per minute of input audio (vendors
 *                            that price per minute)
 * @param timeoutSeconds      per-call HTTP timeout
 */
public record SttModelInfo(
        String provider,
        String modelName,
        Set<String> supportedFormats,
        int maxDurationSeconds,
        Set<String> supportedLanguages,
        @Nullable Double costPerSecond,
        @Nullable Double costPerMinute,
        int timeoutSeconds) {

    /** Per-call HTTP timeout used when the catalog entry doesn't carry one. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 300;

    /** Default per-call audio cap when the catalog entry doesn't set one. */
    public static final int DEFAULT_MAX_DURATION_SECONDS = 3600;

    /** Formats assumed when the catalog entry omits the list. */
    public static final Set<String> DEFAULT_FORMATS = Set.of("wav", "mp3", "m4a", "ogg", "webm", "flac", "aac");

    public SttModelInfo {
        supportedFormats =
                supportedFormats == null || supportedFormats.isEmpty() ? DEFAULT_FORMATS : Set.copyOf(supportedFormats);
        supportedLanguages = supportedLanguages == null ? Set.of() : Set.copyOf(supportedLanguages);
        if (maxDurationSeconds <= 0) {
            maxDurationSeconds = DEFAULT_MAX_DURATION_SECONDS;
        }
        if (timeoutSeconds <= 0) {
            timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        }
    }

    /** Whether {@code language} (ISO-639-1) is accepted. */
    public boolean supportsLanguage(@Nullable String language) {
        if (supportedLanguages.isEmpty() || language == null || language.isBlank()) {
            return true;
        }
        String lang = language.trim().toLowerCase(Locale.ROOT);
        for (String supported : supportedLanguages) {
            String s = supported.toLowerCase(Locale.ROOT);
            if (s.equals(lang) || s.startsWith(lang + "-")) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code format} is an accepted input format. */
    public boolean supportsFormat(String format) {
        return supportedFormats.contains(format.toLowerCase(Locale.ROOT));
    }

    /** Cost in USD for {@code seconds} of audio, or {@code null} when unpriced. */
    @Nullable
    public Double costForSeconds(double seconds) {
        if (costPerSecond != null) {
            return costPerSecond * seconds;
        }
        if (costPerMinute != null) {
            return costPerMinute * (seconds / 60.0);
        }
        return null;
    }
}
