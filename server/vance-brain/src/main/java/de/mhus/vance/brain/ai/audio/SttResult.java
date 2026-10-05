package de.mhus.vance.brain.ai.audio;

import org.jspecify.annotations.Nullable;

/**
 * Result of one transcription call.
 *
 * @param text            the transcript
 * @param language        detected (or requested) ISO-639-1 code, or
 *                        {@code null} when the provider reports none
 * @param durationSeconds duration of the transcribed audio as reported
 *                        by the provider, or {@code null} when unknown
 * @param reportedCostUsd vendor-reported cost in USD, or {@code null}
 *                        when the provider prices nothing
 */
public record SttResult(
        String text,
        @Nullable String language,
        @Nullable Double durationSeconds,
        @Nullable Double reportedCostUsd) {

    public SttResult {
        if (text == null) {
            throw new IllegalArgumentException("text is null");
        }
    }
}
