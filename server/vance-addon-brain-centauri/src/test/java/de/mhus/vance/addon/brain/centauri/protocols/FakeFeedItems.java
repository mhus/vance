package de.mhus.vance.addon.brain.centauri.protocols;

import de.mhus.vance.toolpack.feed.FeedItem;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Minimal entries for tests that only care about id and timestamp. */
final class FakeFeedItems {

    private FakeFeedItems() {
        /* helpers only */
    }

    static FeedItem at(String id, String isoInstant) {
        return new FeedItem(id, Instant.parse(isoInstant), "title-" + id,
                "https://example.test/" + id,
                null, null, null, null, null, null, List.of(), Map.of());
    }
}
