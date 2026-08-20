package de.mhus.vance.toolpack.feed;

import de.mhus.vance.toolpack.facet.Facet;
import de.mhus.vance.toolpack.facet.FacetSelection;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * What a configured source can do — declared, not discovered by trial.
 *
 * <p>Two rules hang off this record:
 * <ul>
 *   <li><b>Pushdown or post-filter.</b> Whatever the source cannot apply
 *       itself, the dispatcher applies afterwards and over-fetches for.
 *       Without the first half a filter silently does nothing; without the
 *       second a page of twenty shrinks to three.
 *   <li><b>Optional means capability-gated.</b> An empty
 *       {@link #signalsAccepted()} makes the UI hide the buttons rather
 *       than offer one that fails. That is the difference between
 *       <i>optional</i> and <i>unreliable</i>.
 * </ul>
 */
public record FeedCapabilities(
        FeedSelectorMode selectorMode,
        Set<FeedSelectorKind> selectorKinds,
        boolean pushdownTextSearch,
        boolean pushdownLanguage,
        boolean pushdownSince,
        boolean supportsNewerDirection,
        boolean carriesFullBody,
        int maxPageSize,
        Set<FeedSignal> signalsAccepted,
        boolean carriesControlUrl,
        Duration capabilitiesTtl,
        /**
         * Dimensions this source can be filtered by — see {@link Facet}.
         *
         * <p>Unlike the {@code pushdown*} flags above there is no
         * post-filter fallback: a declared facet is applied by the source,
         * and an undeclared one takes the source out of the request. That
         * is why it is a list of declarations rather than a boolean —
         * the reader needs the values and their labels to draw a picker.
         */
        List<Facet> facets) {

    /** Fallback TTL when a source does not state one. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    /** Page size assumed when a source declares nothing sensible. */
    public static final int DEFAULT_MAX_PAGE_SIZE = 40;

    /**
     * The same declaration without facets — the shape every source had before
     * facets existed, and still the common one.
     */
    public FeedCapabilities(
            FeedSelectorMode selectorMode,
            Set<FeedSelectorKind> selectorKinds,
            boolean pushdownTextSearch,
            boolean pushdownLanguage,
            boolean pushdownSince,
            boolean supportsNewerDirection,
            boolean carriesFullBody,
            int maxPageSize,
            Set<FeedSignal> signalsAccepted,
            boolean carriesControlUrl,
            Duration capabilitiesTtl) {
        this(selectorMode, selectorKinds, pushdownTextSearch, pushdownLanguage,
                pushdownSince, supportsNewerDirection, carriesFullBody, maxPageSize,
                signalsAccepted, carriesControlUrl, capabilitiesTtl, List.of());
    }

    public FeedCapabilities {
        if (selectorMode == null) {
            throw new IllegalArgumentException("selectorMode is required");
        }
        selectorKinds = selectorKinds == null ? Set.of() : Set.copyOf(selectorKinds);
        signalsAccepted = signalsAccepted == null ? Set.of() : Set.copyOf(signalsAccepted);
        facets = facets == null ? List.of() : List.copyOf(facets);
        if (maxPageSize <= 0) {
            maxPageSize = DEFAULT_MAX_PAGE_SIZE;
        }
        if (capabilitiesTtl == null || capabilitiesTtl.isNegative() || capabilitiesTtl.isZero()) {
            capabilitiesTtl = DEFAULT_TTL;
        }
        if (selectorMode == FeedSelectorMode.FREEFORM && selectorKinds.isEmpty()) {
            throw new IllegalArgumentException(
                    "FREEFORM sources must declare at least one selectorKind — "
                            + "otherwise the configuration UI has no field to render");
        }
    }

    /**
     * A read-only source with a server-side taxonomy and no back channel —
     * the shape most aggregators start with.
     */
    public static FeedCapabilities enumerableReadOnly(int maxPageSize) {
        return new FeedCapabilities(
                FeedSelectorMode.ENUMERABLE, Set.of(FeedSelectorKind.CATEGORY),
                false, false, false, false, false,
                maxPageSize, Set.of(), false, DEFAULT_TTL);
    }

    public boolean acceptsSignals() {
        return !signalsAccepted.isEmpty();
    }

    /** The facet keys this source declared. */
    public Set<String> facetKeys() {
        return FacetSelection.keysOf(facets);
    }

    public boolean accepts(FeedSignal signal) {
        return signalsAccepted.contains(signal);
    }
}
