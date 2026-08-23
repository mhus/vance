package de.mhus.vance.brain.centauri;

import de.mhus.vance.toolpack.feed.FeedDirection;
import de.mhus.vance.toolpack.feed.FeedFilter;
import java.util.LinkedHashSet;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What the reader asked for: a set of streams, a filter, a page size and
 * where to resume.
 *
 * <p>{@code cursor} is the encoded outer cursor from the previous page, or
 * null to start at the top.
 */
public record CentauriPageRequest(
        List<FeedStream> streams,
        FeedFilter filter,
        int pageSize,
        FeedDirection direction,
        @Nullable String cursor) {

    /** Upper bound so one request cannot ask every source for everything. */
    public static final int MAX_PAGE_SIZE = 100;

    public static final int DEFAULT_PAGE_SIZE = 20;

    public CentauriPageRequest {
        // Distinct, order-preserving. The same (sourceId, selector) twice is a
        // configuration mistake, not a request for two of them: it costs the
        // source a second identical fetch, and every entry then arrives twice
        // in the merge, where only the URL deduplication catches it — and
        // counts perfectly good entries as duplicates while doing so.
        streams = streams == null ? List.of() : List.copyOf(new LinkedHashSet<>(streams));
        filter = filter == null ? FeedFilter.none() : filter;
        direction = direction == null ? FeedDirection.OLDER : direction;
        if (pageSize <= 0) {
            pageSize = DEFAULT_PAGE_SIZE;
        }
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);
    }

    public static CentauriPageRequest of(List<FeedStream> streams) {
        return new CentauriPageRequest(
                streams, FeedFilter.none(), DEFAULT_PAGE_SIZE, FeedDirection.OLDER, null);
    }
}
