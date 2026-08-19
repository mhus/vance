package de.mhus.vance.brain.centauri;

import de.mhus.vance.toolpack.feed.FeedItem;

/**
 * A feed entry plus the attribution the reader needs: which source and
 * which stream it came from.
 *
 * <p>Kept next to the item rather than inside it so the contract record
 * stays a pure statement about the entry — the source does not know under
 * which endpoint id an operator configured it.
 */
public record CentauriItem(
        FeedItem item,
        String sourceId,
        String sourceDisplayName,
        String selector) {

    public String streamKey() {
        return new FeedStream(sourceId, selector).key();
    }
}
