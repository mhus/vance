package de.mhus.vance.toolpack.research;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Pointer to a hit's payload. Carried on {@link SearchHit}; the dispatch
 * layer uses {@link #inline()} to decide whether the body is already in
 * the tool-result JSON ({@link ContentInline#EMBED_TEXT}) or whether it
 * has to be loaded on demand by calling
 * {@link SearchProviderInstance#loadContent}
 * ({@link ContentInline#STASH_ON_DEMAND}).
 *
 * <p>{@code stashPath} is null at search time and filled in after the
 * engine asks the provider to load the bytes — at that point the file
 * lives in the project's workspace temp-root, written by
 * {@code ZarniwoopContentStore}.
 */
public record ContentReference(
        String contentId,
        String mimeType,
        long sizeBytes,
        ContentInline inline,
        @Nullable String inlineText,
        @Nullable Path stashPath) {

    public ContentReference {
        if (contentId == null || contentId.isBlank()) {
            throw new IllegalArgumentException("contentId is required");
        }
        if (inline == null) {
            throw new IllegalArgumentException("inline is required");
        }
    }
}
