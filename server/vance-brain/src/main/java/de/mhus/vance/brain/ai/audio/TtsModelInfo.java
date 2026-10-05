package de.mhus.vance.brain.ai.audio;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Static facts about a text-to-speech provider/model pair — voices,
 * languages, output formats, per-character / per-second cost, and a
 * per-call HTTP timeout.
 *
 * <p>Sourced from model documents with {@code kind: tts} and looked up
 * via {@code ModelCatalog.lookupTts}. Costs are declared per character
 * of input text ({@code costPerChar}, the common TTS pricing) or per
 * second of generated audio ({@code costPerSecond}); an empty cost map
 * means "cost unknown" — the call still runs, and the tracker books the
 * vendor-reported cost when the response carries one.
 *
 * @param provider            protocol wire-name
 * @param modelName           provider-specific model id
 * @param supportedVoices     voice descriptors ({@link Voice}); empty =
 *                            the model does not publish a voice list
 * @param supportedLanguages  ISO-639-1 codes; empty = any language
 * @param supportedFormats    accepted {@code format} values
 *                            ({@code mp3}, {@code wav}, {@code pcm}, …)
 * @param maxInputChars       per-call text cap (0 = unknown/default)
 * @param supportsSpeed       whether {@code speed} is honoured
 * @param costPerChar         USD per input character
 * @param costPerSecond       USD per second of generated audio
 * @param timeoutSeconds      per-call HTTP timeout
 */
public record TtsModelInfo(
        String provider,
        String modelName,
        List<Voice> supportedVoices,
        Set<String> supportedLanguages,
        Set<String> supportedFormats,
        int maxInputChars,
        boolean supportsSpeed,
        Double costPerChar,
        Double costPerSecond,
        int timeoutSeconds) {

    /** Per-call HTTP timeout used when the catalog entry doesn't carry one. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 120;

    /** Default per-call text cap when the catalog entry doesn't set one. */
    public static final int DEFAULT_MAX_INPUT_CHARS = 4000;

    /** Formats assumed when the catalog entry omits the list. */
    public static final Set<String> DEFAULT_FORMATS = Set.of("mp3", "wav");

    public TtsModelInfo {
        supportedVoices = supportedVoices == null ? List.of() : List.copyOf(supportedVoices);
        supportedLanguages = supportedLanguages == null ? Set.of() : Set.copyOf(supportedLanguages);
        supportedFormats =
                supportedFormats == null || supportedFormats.isEmpty() ? DEFAULT_FORMATS : Set.copyOf(supportedFormats);
        if (maxInputChars <= 0) {
            maxInputChars = DEFAULT_MAX_INPUT_CHARS;
        }
        if (timeoutSeconds <= 0) {
            timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        }
    }

    /** Whether {@code language} (ISO-639-1) is in the supported set. */
    public boolean supportsLanguage(@org.jspecify.annotations.Nullable String language) {
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

    /** One published voice. */
    public record Voice(
            String id,
            @org.jspecify.annotations.Nullable String locale,
            @org.jspecify.annotations.Nullable String gender,
            @org.jspecify.annotations.Nullable String description) {

        public Voice {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("voice id is blank");
            }
        }

        /** Whether this voice matches {@code language} on its locale prefix. */
        public boolean matchesLanguage(@org.jspecify.annotations.Nullable String language) {
            if (language == null || language.isBlank() || locale == null) {
                return true;
            }
            String lang = language.trim().toLowerCase(Locale.ROOT);
            String l = locale.toLowerCase(Locale.ROOT);
            return l.equals(lang) || l.startsWith(lang + "-");
        }
    }
}
