package de.mhus.vance.addon.brain.centauri.protocols;

import de.mhus.vance.toolpack.feed.FeedItem;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A position plus the entry that position names — the shape a resume token needs
 * when the upstream API's own cursor is <b>inclusive</b>.
 *
 * <h2>Why this exists</h2>
 * Centauri's contract says a cursor resumes <i>strictly after</i> an entry. Real
 * APIs mostly do not: MediaWiki's {@code rccontinue} names the first entry of the
 * next batch, and USGS's {@code endtime} is an inclusive bound. Deriving a cursor
 * from the last delivered entry and handing it back therefore returns that entry
 * a second time — and since Centauri de-duplicates only within a page, the
 * repeat would land on the next page as a visible duplicate.
 *
 * <p>The fix is to carry the anchor's id alongside the position and drop it from
 * the following page. That keeps the adapter stateless: everything needed to
 * de-overlap travels in the cursor.
 *
 * <p>The wire form {@code position|anchorId} is not arbitrary — it is exactly
 * what MediaWiki already uses, so for Wikipedia the encoded cursor doubles as a
 * valid {@code rccontinue}.
 *
 * <p><b>The anchor must always name an entry that was delivered.</b> That is the
 * whole invariant, and it is the one worth stating because a plausible-looking
 * alternative breaks it: MediaWiki's own {@code continue} token names the first
 * entry of the <em>next</em> batch, so adopting it as a cursor makes
 * {@link #dropAnchor} delete an entry nobody has seen. Both adapters therefore
 * derive their cursor from the last entry they returned.
 */
record AnchoredCursor(String position, String anchorId) {

    private static final char SEPARATOR = '|';

    static @Nullable AnchoredCursor parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int cut = raw.indexOf(SEPARATOR);
        if (cut <= 0 || cut == raw.length() - 1) {
            // A cursor without an anchor is still a usable position — it just
            // cannot de-overlap. Tolerated rather than rejected so a cursor from
            // an older build keeps working.
            return new AnchoredCursor(raw.trim(), "");
        }
        return new AnchoredCursor(raw.substring(0, cut).trim(), raw.substring(cut + 1).trim());
    }

    String encode() {
        return position + SEPARATOR + anchorId;
    }

    boolean hasAnchor() {
        return !anchorId.isEmpty();
    }

    /**
     * Drop the entry the cursor pointed at, if the upstream returned it again.
     * Only ever removes the anchor itself — an entry that merely happens to sit
     * at the same position stays.
     */
    static List<FeedItem> dropAnchor(List<FeedItem> items, @Nullable AnchoredCursor cursor) {
        if (cursor == null || !cursor.hasAnchor() || items.isEmpty()) {
            return items;
        }
        List<FeedItem> out = new ArrayList<>(items.size());
        for (FeedItem item : items) {
            if (!item.id().equals(cursor.anchorId())) {
                out.add(item);
            }
        }
        return out;
    }
}
