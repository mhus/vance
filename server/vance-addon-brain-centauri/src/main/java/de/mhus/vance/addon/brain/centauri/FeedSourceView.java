package de.mhus.vance.addon.brain.centauri;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One configured source.
 *
 * <p>{@code error} is set when the source could not be asked what it can do.
 * Reported rather than omitted: a source missing from the list looks like a
 * source that was never configured, which sends the reader to the wrong place
 * to fix it.
 */
@GenerateTypeScript("centauri")
public record FeedSourceView(
        String id,
        String displayName,
        String baseUrl,
        @Nullable FeedCapabilitiesView capabilities,
        List<FeedSelectorView> selectors,
        @Nullable String error) {}
