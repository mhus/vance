package de.mhus.vance.brain.discovery;

/**
 * Immutable snapshot of a rendered source catalog for one tenant /
 * project scope. The {@link #markdown()} block is what gets injected
 * into the {@code how-do-i} recipe's Pebble template; the
 * {@link #contentHash()} is a stable identifier used to detect
 * source-edit drift for cache invalidation.
 */
public record CatalogSnapshot(String markdown, String contentHash) {
}
