package de.mhus.vance.brain.ai;

import de.mhus.vance.brain.ai.ModelInfo.Pricing;
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
 * {@link #maxOutputTokens()}, {@link #ownedBy()}, {@link #pricing()}.
 * All optional; vendors differ wildly in what they return.
 *
 * <p>{@link #pricing()} follows the same observation doctrine as the
 * limits: it is carried <b>only</b> when the listing endpoint itself
 * reports prices (cortecs' {@code /v1/models} ships a pricing block in
 * EUR per MTok; OpenRouter reports per-token USD, which the parser
 * converts). It is never inferred from anywhere else and never guessed —
 * the auto layer outranks bundled, a wrong price would shadow the
 * operator's correct one.
 *
 * <p><b>Deliberately absent: {@code kind}</b> (and capabilities). Those
 * are <em>classifications</em>, not observations — they belong to the
 * operator-owned manual layer. The auto layer sits
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
        @Nullable Integer contextWindowTokens,
        @Nullable Integer maxOutputTokens,
        @Nullable String ownedBy,
        @Nullable Pricing pricing) {

    /** Back-compat for callers predating the pricing observation. */
    public DiscoveredModelInfo(
            String wireName,
            @Nullable Integer contextWindowTokens,
            @Nullable Integer maxOutputTokens,
            @Nullable String ownedBy) {
        this(wireName, contextWindowTokens, maxOutputTokens, ownedBy, null);
    }

    public DiscoveredModelInfo {
        if (wireName == null || wireName.isBlank()) {
            throw new IllegalArgumentException("wireName is required");
        }
    }

    /** Wire-name only — every other field stays unknown. */
    public static DiscoveredModelInfo of(String wireName) {
        return new DiscoveredModelInfo(wireName, null, null, null, null);
    }

    /** Wire-name plus a discovered context window. */
    public static DiscoveredModelInfo withWindow(String wireName, int contextWindowTokens) {
        return new DiscoveredModelInfo(wireName, contextWindowTokens, null, null, null);
    }
}
