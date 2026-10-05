package de.mhus.vance.brain.hotblack;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/** Successful result of a {@link HotblackService#compose} call. */
@Value
@Builder
public class GenerateMusicResult {

    /** Document path the audio was written to. */
    String path;

    /** Mime type of the stored bytes. */
    String mimeType;

    /** Logical content size in bytes. */
    long sizeBytes;

    /** Resolved {@code <instance>:<modelName>} that produced the audio. */
    String modelUsed;

    /** Total wall-clock time from request entry to commit. */
    long durationMs;

    /** Booked cost in USD, or {@code null} when unpriced. */
    @Nullable
    Double costUsd;

    /** Auto-generated or caller-supplied title attached to the document. */
    @Nullable
    String title;
}
