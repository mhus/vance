package de.mhus.vance.toolpack.research;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One result row. Per-modality extras (e.g. {@code imageUrl},
 * {@code thumbnailUrl}, {@code embedFence} for videos,
 * {@code sizeBytes} for PDFs) live in {@link #extras()} so the record
 * itself doesn't have to grow a field per modality.
 *
 * <p>{@code content} is null when the hit only carries metadata —
 * typical for an organic web result whose body has not been fetched
 * yet. Set to a {@link ContentReference} with
 * {@link ContentInline#EMBED_TEXT} for small inline bodies, or with
 * {@link ContentInline#STASH_ON_DEMAND} for payloads the engine has to
 * pull through {@link SearchProviderInstance#loadContent}.
 */
public record SearchHit(
        String title,
        String url,
        @Nullable String snippet,
        @Nullable String source,
        SearchModality modality,
        @Nullable ContentReference content,
        Map<String, Object> extras) {

    public SearchHit {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
        if (modality == null) {
            throw new IllegalArgumentException("modality is required");
        }
        extras = extras == null ? Map.of() : Map.copyOf(extras);
    }
}
