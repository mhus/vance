package de.mhus.vance.toolpack.research;

import de.mhus.vance.toolpack.facet.FacetSelection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One search request as it leaves the frontend tool and enters the
 * dispatcher. {@code pinnedProviderId} pins an instance directly —
 * only honoured when {@link #tier()} is {@link SearchTier#EXPERT}, in
 * which case fallback resolution is skipped and the dispatcher uses
 * exactly that instance (or returns empty if it's unavailable).
 *
 * <p>{@code expertParams} carries the EXPERT-tier filter surface
 * ({@code site}, {@code filetype}, {@code dateFrom}, {@code dateTo},
 * {@code domain} …). Protocols pick the ones they support; unknown
 * keys are ignored silently — schema validation at the tool boundary
 * is the gate for typos.
 *
 * <p>{@code facets} is the structured counterpart and behaves the
 * opposite way: a provider that has not declared a selected key does
 * not ignore it, it is skipped for this request. Unlike
 * {@code expertParams} the reader knows what a facet means — it drew
 * the picker from the provider's declared values — so silently
 * answering a question that was not asked would be the wrong kind of
 * tolerance. See {@link de.mhus.vance.toolpack.facet.FacetSelection}.
 */
public record SearchRequest(
        String query,
        SearchModality modality,
        SearchTier tier,
        int maxResults,
        @Nullable Locale locale,
        @Nullable String pinnedProviderId,
        Map<String, Object> expertParams,
        Map<String, List<String>> facets) {

    /** The same request without facets. */
    public SearchRequest(
            String query,
            SearchModality modality,
            SearchTier tier,
            int maxResults,
            @Nullable Locale locale,
            @Nullable String pinnedProviderId,
            Map<String, Object> expertParams) {
        this(query, modality, tier, maxResults, locale, pinnedProviderId, expertParams,
                FacetSelection.none());
    }

    public SearchRequest {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query is required");
        }
        if (modality == null) {
            throw new IllegalArgumentException("modality is required");
        }
        if (tier == null) {
            throw new IllegalArgumentException("tier is required");
        }
        if (maxResults <= 0) {
            throw new IllegalArgumentException("maxResults must be > 0");
        }
        expertParams = expertParams == null ? Map.of() : Map.copyOf(expertParams);
        facets = FacetSelection.normalize(facets);
    }

    /** Convenience for NORMAL tier without expert filters. */
    public static SearchRequest normal(
            String query, SearchModality modality, int maxResults) {
        return new SearchRequest(
                query, modality, SearchTier.NORMAL, maxResults,
                null, null, Map.of());
    }
}
