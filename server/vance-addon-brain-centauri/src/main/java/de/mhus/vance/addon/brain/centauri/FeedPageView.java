package de.mhus.vance.addon.brain.centauri;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One page of the mixed feed.
 *
 * <p><b>{@code items} may be empty while {@code hasMore} is true</b>, and the
 * client must keep pulling in that case — it happens whenever the filter rejects
 * everything a round fetched. Treating an empty page as the end would cut the
 * scroll short at the first strict filter.
 */
@GenerateTypeScript("centauri")
public record FeedPageView(
        List<FeedItemView> items,
        @Nullable String nextCursor,
        boolean hasMore,
        List<FeedNoteView> notes,
        int droppedByFilter,
        int droppedAsDuplicate) {}
