package de.mhus.vance.brain.ai.audio;

import org.jspecify.annotations.Nullable;

/**
 * Result of one audio-analysis call — the model's free-text description
 * of the audio (genre, instrumentation, tempo, mood, structure, …).
 *
 * @param text            the analysis; never {@code null}
 * @param reportedCostUsd vendor-reported cost in USD, or {@code null}
 *                        when the provider prices nothing
 */
public record AnalysisResult(String text, @Nullable Double reportedCostUsd) {

    public AnalysisResult {
        if (text == null) {
            throw new IllegalArgumentException("text is null");
        }
    }
}
