package de.mhus.vance.addon.brain.centauri;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A request for one page.
 *
 * <p>Two ways in, and both are wanted: with {@code folder} the server reads the
 * stored configuration, which is what the app does; with explicit
 * {@code streams} the caller composes a one-off view, which is what a preview in
 * the configuration tab needs before anything is saved.
 *
 * <p>A POST rather than a GET because the filter is structured. Squeezing
 * keyword lists into a query string would mean inventing an encoding for them.
 */
@GenerateTypeScript("centauri")
public record FeedPageRequest(
        @Nullable String folder,
        List<FeedStreamView> streams,
        @Nullable FeedFilterView filter,
        int pageSize,
        @Nullable String cursor,
        @Nullable String direction) {}
