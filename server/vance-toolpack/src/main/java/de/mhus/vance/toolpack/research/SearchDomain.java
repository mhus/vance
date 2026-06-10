package de.mhus.vance.toolpack.research;

/**
 * The subject area an instance specialises in. Used by the
 * {@code ZarniwoopResearchService} plan recipe to bias source-affinity
 * scoring — a {@code academic} question should weight
 * {@code ACADEMIC}-tagged instances higher than {@code NEWS}-tagged
 * ones, even when both can serve the request.
 *
 * <p>Carried in {@link SearchProviderInstance#domains()}; the dispatcher
 * itself does not filter by domain (that is the recipe's job).
 */
public enum SearchDomain {
    GENERAL,
    NEWS,
    ACADEMIC,
    ENCYCLOPEDIA,
    INTERNAL,
    BOOK,
    CODE
}
