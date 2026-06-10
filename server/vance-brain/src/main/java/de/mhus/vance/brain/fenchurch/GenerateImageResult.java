package de.mhus.vance.brain.fenchurch;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Successful result of a {@link FenchurchService#generate} call.
 * Failures throw {@link FenchurchException} instead — the tool
 * layer maps that into the JSON error shape documented in the
 * planning doc.
 */
@Value
@Builder
public class GenerateImageResult {

    /** Document path the image was written to. */
    String path;

    /** Mime type of the stored bytes. */
    String mimeType;

    /** Logical content size in bytes. */
    long sizeBytes;

    /** Resolved {@code <provider>:<modelName>} that produced the image. */
    String modelUsed;

    /** Total wall-clock time from request entry to commit. */
    long durationMs;

    /** Auto-generated or caller-supplied title attached to the document. */
    @Nullable String title;
}
