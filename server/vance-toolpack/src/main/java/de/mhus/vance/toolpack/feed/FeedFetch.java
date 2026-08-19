package de.mhus.vance.toolpack.feed;

import org.jspecify.annotations.Nullable;

/**
 * One request against one stream of one source.
 *
 * <p>{@code cursor} is opaque and source-owned. Offset paging is
 * deliberately not part of this contract: in a stream that keeps gaining
 * entries at the top, offsets produce both duplicates and gaps.
 *
 * <p>{@code pushdown} is the filter subset this source declared it can
 * apply (see {@link FeedFilter#projectTo(FeedCapabilities)}). It is an
 * optimisation to reduce fetch volume — the dispatcher re-applies the full
 * filter regardless.
 *
 * <p>{@code actor} is null for anonymous calls, and every source must
 * answer anyway. A source that needs the pseudonym to respond at all
 * breaks every scheduler-driven digest.
 */
public record FeedFetch(
        String selector,
        @Nullable String cursor,
        FeedDirection direction,
        int limit,
        FeedFilter pushdown,
        @Nullable FeedActor actor) {

    public FeedFetch {
        if (selector == null) {
            selector = "";
        }
        if (direction == null) {
            direction = FeedDirection.OLDER;
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0, was " + limit);
        }
        if (pushdown == null) {
            pushdown = FeedFilter.none();
        }
    }
}
