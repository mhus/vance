package de.mhus.vance.brain.ai;

import org.jspecify.annotations.Nullable;

/**
 * One model returned by a provider's listing endpoint
 * ({@code /v1/models}, {@code models.list}, {@code /api/tags}, …).
 * Whatever-the-vendor-gives, normalised to a record the discovery
 * service can dump into a per-model YAML doc.
 *
 * <p>All fields beyond {@link #wireName()} are optional — most
 * vendor APIs return little more than the id. Missing fields stay
 * empty in the resulting doc; the {@link ModelCatalog} cascade
 * inherits them from the bundled / manual layer at lookup time.
 */
public record DiscoveredModelInfo(
        String wireName,
        @Nullable Integer contextWindowTokens,
        @Nullable String kind) {

    public DiscoveredModelInfo {
        if (wireName == null || wireName.isBlank()) {
            throw new IllegalArgumentException("wireName is required");
        }
    }

    /** Wire-name only — every other field stays unknown. */
    public static DiscoveredModelInfo of(String wireName) {
        return new DiscoveredModelInfo(wireName, null, null);
    }

    /** Wire-name plus a discovered context window. {@code kind} stays unknown. */
    public static DiscoveredModelInfo withWindow(String wireName, int contextWindowTokens) {
        return new DiscoveredModelInfo(wireName, contextWindowTokens, null);
    }
}
