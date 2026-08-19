package de.mhus.vance.brain.centauri;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One page of a mixed feed.
 *
 * <p>{@code items} may be empty while {@code hasMore} is true, and the client
 * must keep pulling in that case: it happens whenever the post-filter rejects
 * everything a round fetched. Treating an empty page as the end of the stream
 * would cut the scroll short at the first strict filter.
 */
public record CentauriPage(
        List<CentauriItem> items,
        @Nullable String nextCursor,
        boolean hasMore,
        List<CentauriNote> notes,
        int droppedByFilter,
        int droppedAsDuplicate) {

    public CentauriPage {
        items = items == null ? List.of() : List.copyOf(items);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public static CentauriPage empty(List<CentauriNote> notes) {
        return new CentauriPage(List.of(), null, false, notes, 0, 0);
    }
}
