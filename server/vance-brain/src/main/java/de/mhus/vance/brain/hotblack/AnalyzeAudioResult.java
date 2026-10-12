package de.mhus.vance.brain.hotblack;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/** Successful result of a {@link HotblackService#analyze} call. */
@Value
@Builder
public class AnalyzeAudioResult {

    /** The analysis — the model's free-text description answering the prompt. */
    String analysis;

    /** Effective answer language (requested or {@code chat.language} default). */
    @Nullable
    String language;

    /** Resolved {@code <instance>:<modelName>} that produced the analysis. */
    String modelUsed;

    /** Booked cost in USD, or {@code null} when unpriced. */
    @Nullable
    Double costUsd;

    /** Document path of the analysis text document, when {@code path} was supplied. */
    @Nullable
    String analysisPath;

    /** Total wall-clock time from request entry to result. */
    long durationMs;
}
