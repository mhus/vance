package de.mhus.vance.addon.brain.centauri;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/**
 * A dimension one source can be filtered by, as the configuration and the
 * facet bar see it.
 *
 * <p>{@code values} is what travelled with the declaration. For a
 * {@code lazyChildren} facet that is only the top level and the rest is
 * fetched a level at a time — the reason the flag is on the wire at all is so
 * the UI knows whether an empty child list means „none" or „not asked yet".
 */
@GenerateTypeScript("centauri")
public record FeedFacetView(
        String key,
        String label,
        boolean hierarchical,
        boolean lazyChildren,
        List<FeedFacetValueView> values) {}
