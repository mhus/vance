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
 *
 * <p><b>Deliberately absent: {@code kind}</b> (and pricing, and
 * capabilities). Those are <em>classifications</em>, not observations —
 * they belong to the operator-owned manual layer. The auto layer sits
 * <em>above</em> the bundled layer in the {@link ModelCatalog} cascade
 * (project-auto → _tenant-auto → bundled), so anything asserted here
 * silently shadows a correct bundled classification. A listing endpoint
 * that reports {@code gemini-2.5-flash-image} as chat-capable would
 * otherwise erase its {@code kind: image} and make it vanish from every
 * image-model picker — see the {@code kind}-free {@code writeAutoDoc}
 * in {@code ModelDiscoveryService}.
 */
public record DiscoveredModelInfo(
        String wireName,
        @Nullable Integer contextWindowTokens) {

    public DiscoveredModelInfo {
        if (wireName == null || wireName.isBlank()) {
            throw new IllegalArgumentException("wireName is required");
        }
    }

    /** Wire-name only — every other field stays unknown. */
    public static DiscoveredModelInfo of(String wireName) {
        return new DiscoveredModelInfo(wireName, null);
    }

    /** Wire-name plus a discovered context window. */
    public static DiscoveredModelInfo withWindow(String wireName, int contextWindowTokens) {
        return new DiscoveredModelInfo(wireName, contextWindowTokens);
    }
}
