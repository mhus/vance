package de.mhus.vance.toolpack.feed;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One page from a single stream, as returned by
 * {@link FeedSourceInstance#fetch(FeedFetch)}.
 *
 * <p>Items must come back in the direction that was asked for and sorted
 * by {@code publishedAt} — descending for {@link FeedDirection#OLDER},
 * ascending for {@link FeedDirection#NEWER}. Personalisation may influence
 * <b>which</b> items appear, never their order: the cross-source merge
 * relies on the ordering key being globally comparable, and a per-reader
 * ranking would produce quietly wrong sequences rather than visibly
 * broken ones.
 *
 * <p>{@code nextCursor} is opaque to Centauri and belongs to the source.
 * {@code hasMore == false} marks the stream exhausted for this direction;
 * the merge then stops asking.
 */
public record FeedPage(
        List<FeedItem> items,
        @Nullable String nextCursor,
        boolean hasMore) {

    public FeedPage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static FeedPage empty() {
        return new FeedPage(List.of(), null, false);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }
}
