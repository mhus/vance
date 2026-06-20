package de.mhus.vance.brain.discovery;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Immutable snapshot of a rendered source catalog for one tenant /
 * project scope. The {@link #markdown()} block is what gets injected
 * into the {@code how-do-i} recipe's Pebble template; the
 * {@link #contentHash()} is a stable identifier used to detect
 * source-edit drift for cache invalidation.
 *
 * <p>{@link #entries()} carries side-table metadata about each
 * {@code ### <name>} section in the markdown — used by
 * {@link CatalogFilter} to drop tools the calling engine isn't
 * allowed to invoke and manuals whose {@code requires-tools} header
 * isn't satisfied. The markdown stays the full unfiltered render;
 * filtering happens at use time so the cache stays engine-agnostic.
 */
public record CatalogSnapshot(
        String markdown,
        String contentHash,
        Map<String, EntrySpec> entries) {

    public CatalogSnapshot {
        entries = entries == null ? Map.of() : Collections.unmodifiableMap(entries);
    }

    /** Two-arg backward-compat constructor — no entry metadata. */
    public CatalogSnapshot(String markdown, String contentHash) {
        this(markdown, contentHash, Map.of());
    }

    /**
     * Per-entry metadata. {@code type} mirrors the catalog section
     * the entry was rendered under ({@code tool}, {@code manual},
     * {@code skill}). {@code requiredTools} lists tool names that
     * must be in the calling engine's allow-set for the entry to
     * stay visible; empty set means "no requirement".
     */
    public record EntrySpec(String type, Set<String> requiredTools) {
        public EntrySpec {
            requiredTools = requiredTools == null
                    ? Set.of() : Set.copyOf(requiredTools);
        }
    }
}
