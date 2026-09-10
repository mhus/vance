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
 * The fields beyond {@link #wireName()} are <em>observations</em> the
 * listing endpoint actually reports: {@link #contextWindowTokens()},
 * {@link #maxOutputTokens()}, {@link #ownedBy()}. All optional; vendors
 * differ wildly in what they return.
 *
 * <p><b>Deliberately absent: {@code kind}, pricing, capabilities.</b>
 * Those are <em>owned by other sources</em>, not by the listing
 * endpoint: model kind is a classification the operator owns, and
 * prices come from the vendor's price sheet — a different source, so
 * they live in the operator-owned manual layer
 * ({@code _vance/model/**}), never in the auto docs. This separation is
 * also protective: the auto layer sits <em>above</em> the bundled layer
 * in the {@link ModelCatalog} cascade (project-auto → _tenant-auto →
 * bundled), so a gateway's price block (some ship one — cortecs in EUR,
 * OpenRouter in per-token USD) would silently shadow a curated bundled
 * USD price whenever instance names line up, mixing currencies in the
 * usage report. A listing endpoint that reports
 * {@code gemini-2.5-flash-image} as chat-capable would likewise erase
 * its {@code kind: image} and make it vanish from every image-model
 * picker — see the {@code kind}- and pricing-free {@code writeAutoDoc}
 * in {@code ModelDiscoveryService}.
 */
public record DiscoveredModelInfo(
        String wireName,
        @Nullable Integer contextWindowTokens,
        @Nullable Integer maxOutputTokens,
        @Nullable String ownedBy) {

    public DiscoveredModelInfo {
        if (wireName == null || wireName.isBlank()) {
            throw new IllegalArgumentException("wireName is required");
        }
    }

    /** Wire-name only — every other field stays unknown. */
    public static DiscoveredModelInfo of(String wireName) {
        return new DiscoveredModelInfo(wireName, null, null, null);
    }

    /** Wire-name plus a discovered context window. */
    public static DiscoveredModelInfo withWindow(String wireName, int contextWindowTokens) {
        return new DiscoveredModelInfo(wireName, contextWindowTokens, null, null);
    }
}
