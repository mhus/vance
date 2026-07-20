package de.mhus.vance.addon.brain.wiki;

import org.jspecify.annotations.Nullable;

/**
 * One {@code [[Wikilink]]} occurrence extracted from a page body.
 * {@code target} is the raw link target as written ({@code Foo},
 * {@code Space/Foo}); {@code label} is the optional display text after a
 * pipe ({@code [[Foo|see Foo]]}).
 */
public record WikiLink(
        String target,
        @Nullable String label) {
}
