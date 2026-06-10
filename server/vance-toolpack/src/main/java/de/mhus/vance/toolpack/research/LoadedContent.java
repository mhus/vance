package de.mhus.vance.toolpack.research;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Result of {@link SearchProviderInstance#loadContent}: the on-disk
 * location of the loaded payload inside the project's workspace
 * temp-root and — optionally — an extracted text representation when
 * the protocol can produce one cheaply (Wikipedia parsed body, HTML
 * stripped to text).
 *
 * <p>The path lives in the project workspace and is reaped by
 * {@code WorkspaceService.suspendAll}; consumers should not hold the
 * path across the project lifecycle.
 */
public record LoadedContent(
        String mimeType,
        Path stashPath,
        @Nullable String extractedText) {

    public LoadedContent {
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("mimeType is required");
        }
        if (stashPath == null) {
            throw new IllegalArgumentException("stashPath is required");
        }
    }
}
