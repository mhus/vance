package de.mhus.vance.toolpack.research;

import java.util.Optional;
import java.util.Set;

/**
 * A concrete, configured endpoint produced by a
 * {@link SearchProtocol#instantiate} call. Instances are <b>not</b>
 * Spring beans — {@code SearchProviderFactory} builds them per project
 * from {@code research.endpoint.<id>.*} settings and keeps them in a
 * project-scoped cache.
 *
 * <p>{@link #id()} returns the endpoint name ({@code "serper-main"},
 * {@code "serper-eu"}, {@code "wiki-de"}), <i>not</i> the protocol
 * name. Cooldown subjects, log paths and frontend-tool output all key
 * on the endpoint id.
 *
 * <p>Implementations must be safe to call from multiple threads; the
 * dispatcher does not serialise across calls for the same instance.
 * Persistent state (HTTP client pools, sockets) belongs in the
 * instance — {@link #dispose()} is called when the project is
 * suspended and the cache lets go of the reference.
 */
public interface SearchProviderInstance {

    /** Endpoint id from {@code research.endpoint.<id>}. */
    String id();

    /** Display name for UI/logs. */
    String displayName();

    /** Modalities this configured endpoint can serve. */
    Set<SearchModality> modalities();

    /** Subject-area hints used by the research recipe; never null. */
    Set<SearchDomain> domains();

    /** Tiers this configured endpoint can serve. */
    Set<SearchTier> tiers();

    /** Snapshot of usability in the given scope. */
    ProviderAvailability availability(SearchScope scope);

    /**
     * Current quota of this endpoint. Endpoints without a quota
     * endpoint return {@link Optional#empty()} — the dispatcher will
     * skip the proactive zero-quota gate for those.
     */
    Optional<QuotaStatus> currentQuota(SearchScope scope);

    /**
     * Run the search. Throws on hard upstream failures; the dispatcher
     * routes the throwable to {@code AgrajagChecker} which classifies
     * it and may set a cooldown.
     */
    SearchResult search(SearchRequest req, SearchScope scope);

    /**
     * Fetch the body of a previously-returned hit. The bytes land in
     * the project workspace temp-root via {@code ZarniwoopContentStore}
     * — the returned {@link LoadedContent} carries the on-disk path
     * the engine attaches to the next LLM call.
     *
     * <p>Default implementation throws — only protocols that emit
     * {@link ContentInline#STASH_ON_DEMAND} references need to override.
     */
    default LoadedContent loadContent(ContentReference ref, SearchScope scope) {
        throw new UnsupportedOperationException(
                "loadContent not implemented for instance " + id());
    }

    /** Optional prompt hint the engine surfaces to the LLM. */
    default String promptHint() {
        return "";
    }

    /**
     * Free-text status line for operator-facing UI. Examples:
     * {@code "1552 credits remaining"} for a Serper endpoint with a
     * usable {@code /account} response, {@code "rate-limited until
     * 14:32:10Z"} for a backoff in progress. Returns {@code null} (the
     * default) when there's nothing to add beyond the structured
     * {@link #availability(SearchScope)} and {@link #currentQuota(SearchScope)}.
     */
    default @org.jspecify.annotations.Nullable String statusText(SearchScope scope) {
        return null;
    }

    /**
     * Called by {@code SearchProviderFactory} when the project the
     * instance was built for is suspended. Default is a no-op;
     * protocols with HTTP client pools or persistent sockets close them
     * here. Must not throw.
     */
    default void dispose() {
        /* no-op */
    }
}
