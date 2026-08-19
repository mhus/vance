package de.mhus.vance.toolpack.feed;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One entry in a stream.
 *
 * <p>{@code publishedAt} is <b>mandatory</b>. A source without a timestamp
 * cannot take part in a merged stream at all — the merge across sources
 * needs one globally comparable ordering key, and there is no sensible
 * substitute. This is the contract requirement that makes
 * {@code FeedMerger} possible.
 *
 * <p>{@code id} must be stable for the same entry across requests: it is
 * the tie-break of last resort when two items share a timestamp, so an
 * id that changes between pages produces duplicated or skipped rows in
 * the endless scroll.
 *
 * <p>{@code controlUrl} is the escape hatch for everything the closed
 * signal set does not model: a deep link into the source's own UI for
 * this entry. It is remote-supplied and therefore validated before it
 * ever reaches an {@code <a href>} — https only and host-matched against
 * the instance base URL.
 */
public record FeedItem(
        String id,
        Instant publishedAt,
        String title,
        String url,
        @Nullable String summary,
        @Nullable String body,
        @Nullable String author,
        @Nullable String language,
        @Nullable String imageUrl,
        @Nullable String controlUrl,
        List<String> tags,
        Map<String, Object> extras) {

    public FeedItem {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("feed item id is required");
        }
        if (publishedAt == null) {
            throw new IllegalArgumentException("feed item publishedAt is required (item " + id + ")");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("feed item url is required (item " + id + ")");
        }
        if (title == null || title.isBlank()) {
            title = url;
        }
        tags = tags == null ? List.of() : List.copyOf(tags);
        extras = extras == null ? Map.of() : Map.copyOf(extras);
    }
}
