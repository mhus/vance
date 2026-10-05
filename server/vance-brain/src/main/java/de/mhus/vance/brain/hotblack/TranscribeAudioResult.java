package de.mhus.vance.brain.hotblack;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/** Successful result of a {@link HotblackService#transcribe} call. */
@Value
@Builder
public class TranscribeAudioResult {

    /** The transcript. */
    String text;

    /** Effective language (requested or detected). */
    @Nullable
    String language;

    /** Duration of the transcribed audio in seconds, when reported. */
    @Nullable
    Double durationSeconds;

    /** Resolved {@code <instance>:<modelName>} that produced the transcript. */
    String modelUsed;

    /** Booked cost in USD, or {@code null} when unpriced. */
    @Nullable
    Double costUsd;

    /** Document path of the transcript text document, when {@code path} was supplied. */
    @Nullable
    String transcriptPath;

    /** Total wall-clock time from request entry to result. */
    long durationMs;
}
