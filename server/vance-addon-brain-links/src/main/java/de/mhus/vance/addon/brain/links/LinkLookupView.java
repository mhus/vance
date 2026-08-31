package de.mhus.vance.addon.brain.links;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Answer of {@code GET /addon/links/entry/lookup} — is this one page in the
 * list, and what does the list say about it.
 *
 * <p>The read counterpart to capture, and the reason the capture profile does
 * not need {@code /scan}: an extension that wants to show a filled or empty
 * badge per page asks about <em>one</em> URL. Answering that by transferring the
 * whole list on every page load would be the wrong shape, and giving a capture
 * credential the whole list to get one bit would be the wrong grant.
 *
 * <p>{@code found == false} leaves every other field empty. That is the normal
 * answer, not an error: most pages are not in the list.
 */
public record LinkLookupView(
        boolean found,
        String url,
        @Nullable String title,
        @Nullable String group,
        List<String> tags,
        @Nullable String note,
        @Nullable String addedAt,
        @Nullable String viewedAt) {

    /** The "not in this list" answer, carrying the normalised URL that was asked about. */
    public static LinkLookupView notFound(String url) {
        return new LinkLookupView(false, url, null, null, List.of(), null, null, null);
    }

    public static LinkLookupView of(LinkEntry e) {
        return new LinkLookupView(true, e.url(), e.title(), e.group(), e.tags(), e.note(),
                e.addedAt() == null ? null : e.addedAt().toString(),
                e.viewedAt() == null ? null : e.viewedAt().toString());
    }
}
