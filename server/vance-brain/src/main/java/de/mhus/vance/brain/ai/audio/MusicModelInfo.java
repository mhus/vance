package de.mhus.vance.brain.ai.audio;

import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Static facts about a music / non-speech audio generation
 * provider/model pair — output formats, clip-length cap, lyric
 * languages, and cost.
 *
 * <p>Sourced from model documents with {@code kind: music} and looked
 * up via {@code ModelCatalog.lookupMusic}. Cost is declared per second
 * of generated audio ({@code costPerSecond}) or per clip
 * ({@code costPerTrack}); clip-priced models with vendor-reported
 * costs book the response's {@code usage.cost} instead.
 *
 * @param provider            protocol wire-name
 * @param modelName           provider-specific model id
 * @param supportedFormats    accepted {@code format} values
 * @param maxDurationSeconds  longest clip the model generates
 * @param supportedLanguages  lyric languages (ISO-639-1); empty = any
 * @param costPerSecond       USD per second of generated audio
 * @param costPerTrack        USD per generated clip
 * @param timeoutSeconds      per-call HTTP timeout
 */
public record MusicModelInfo(
        String provider,
        String modelName,
        Set<String> supportedFormats,
        int maxDurationSeconds,
        Set<String> supportedLanguages,
        @Nullable Double costPerSecond,
        @Nullable Double costPerTrack,
        int timeoutSeconds) {

    /** Per-call HTTP timeout used when the catalog entry doesn't carry one. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 600;

    /** Default clip-length cap when the catalog entry doesn't set one. */
    public static final int DEFAULT_MAX_DURATION_SECONDS = 300;

    /** Formats assumed when the catalog entry omits the list. */
    public static final Set<String> DEFAULT_FORMATS = Set.of("mp3", "wav");

    public MusicModelInfo {
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

    /** Whether {@code language} (ISO-639-1) is among the lyric languages. */
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

    /** Whether {@code format} is in the supported set. */
    public boolean supportsFormat(String format) {
        return supportedFormats.contains(format.toLowerCase(Locale.ROOT));
    }
}
