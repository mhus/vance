package de.mhus.vance.toolpack.feed;

import java.util.List;
import java.util.Optional;

/**
 * A concrete, configured source produced by
 * {@link FeedProtocol#instantiate}. Instances are <b>not</b> Spring beans —
 * {@code FeedSourceFactory} builds them per project from
 * {@code centauri.endpoint.<id>.*} settings and keeps them in a
 * project-scoped cache.
 *
 * <p>{@link #id()} is the endpoint name ({@code "hrafnagud-main"},
 * {@code "mastodon-social"}), not the protocol name. Cooldown subjects,
 * stream keys and configuration UI all key on it.
 *
 * <p>Implementations must be safe to call from multiple threads: the
 * dispatcher fetches a page from every stream of a feed concurrently and
 * does not serialise across calls for the same instance. Persistent state
 * (HTTP clients, connection pools) belongs in the instance;
 * {@link #dispose()} is called when the project is suspended.
 */
public interface FeedSourceInstance {

    /** Endpoint id from {@code centauri.endpoint.<id>}. */
    String id();

    /** Display name for configuration UI and logs. */
    String displayName();

    /**
     * The source's base URL, used to host-match {@link FeedItem#controlUrl()}
     * before it ever becomes a link.
     */
    String baseUrl();

    /**
     * What this source can do. Cached by the dispatcher for
     * {@link FeedCapabilities#capabilitiesTtl()} — it describes the source,
     * not the reader, so the cache is shared across users and no actor is
     * involved.
     */
    FeedCapabilities capabilities();

    /**
     * The finite selector list of an {@link FeedSelectorMode#ENUMERABLE}
     * source. Empty for {@code FREEFORM} and {@code NONE}. Reader-independent,
     * like {@link #capabilities()}.
     */
    List<FeedSelector> listSelectors();

    /**
     * One level of a facet's value tree, for a source whose taxonomy is too
     * large to ship inline (see {@link de.mhus.vance.toolpack.facet.Facet}).
     *
     * <p>{@code parentId} null means the top level. The default returns
     * nothing, which is right for every source that declares its values
     * inline — and the dispatcher only asks a facet that said
     * {@code lazyChildren}.
     */
    default List<de.mhus.vance.toolpack.facet.FacetValue> listFacetValues(
            String key, @org.jspecify.annotations.Nullable String parentId) {
        return List.of();
    }

    /**
     * Validate a free-text selector, returning a human-readable complaint or
     * {@link Optional#empty()} when it is usable.
     *
     * <p>Mandatory in spirit for {@code FREEFORM} sources: without it
     * somebody types a tag with a trailing space and gets an empty stream
     * and no explanation. The default accepts anything non-blank, which is
     * right for {@code ENUMERABLE} and {@code NONE}.
     */
    default Optional<String> validateSelector(String raw) {
        return raw == null || raw.isBlank()
                ? Optional.of("selector must not be empty")
                : Optional.empty();
    }

    /**
     * Fetch one page of one stream. Throws {@link FeedException} on hard
     * upstream failures; the dispatcher classifies the throwable and may set
     * a cooldown.
     */
    FeedPage fetch(FeedFetch request);

    /**
     * The cursor that resumes immediately after {@code item}.
     *
     * <p>This exists because a page-level {@code nextCursor} alone cannot
     * express the cut the merge actually makes. When five streams are mixed
     * and a source contributed three of twenty fetched entries to the page,
     * the next request must resume after entry three — and an opaque
     * page-end cursor cannot say that. So a cursor has to be derivable from
     * a single entry, and the source is the only party that knows how.
     *
     * <p>The default answers that in the one way that works for any source:
     * {@link FeedItem#cursor()} when the source supplied a token for this
     * entry, and the item id otherwise. The id alone is right for id-based
     * paging (Mastodon's {@code max_id}); it is <b>wrong</b> for a source
     * paging by {@code (publishedAt, id)}, and wrong silently — such a source
     * reads a bare id as „start from the beginning" and the merged scroll
     * repeats a page forever instead of advancing. That is why the token
     * travels on the item: the protocol cannot derive it, only the source can.
     *
     * <p>A protocol whose paging is neither may still override this.
     */
    default String cursorAfter(FeedItem item) {
        return item.cursor() == null ? item.id() : item.cursor();
    }

    /**
     * One entry in full — the same record a page carries, with whatever the
     * listing left out: the body, richer {@code extras}, a longer summary.
     *
     * <p>Deliberately not „load the body". A page entry is a teaser, which is
     * what makes twenty of them cheap; what a reader wants when it opens one
     * is the entry, and a source that has more to say than text would
     * otherwise have nowhere to say it. One type for both means the caller
     * replaces what it holds instead of merging two shapes.
     *
     * <p>Empty for an id the source does not know — normal for an entry that
     * aged out between the page and the click.
     */
    default Optional<FeedItem> loadItem(
            String itemId, @org.jspecify.annotations.Nullable FeedActor actor) {
        return Optional.empty();
    }

    /**
     * Send a back-channel signal (§12a of the plan). The default refuses,
     * which is the honest answer for every source that declares an empty
     * {@link FeedCapabilities#signalsAccepted()} — and the dispatcher checks
     * the capability before calling, so this is a second line rather than
     * the gate.
     */
    default FeedSignalOutcome sendSignal(FeedSignalRequest request) {
        return FeedSignalOutcome.UNSUPPORTED;
    }

    /**
     * Called when the project this instance was built for is suspended.
     * Must not throw.
     */
    default void dispose() {
        /* no-op */
    }
}
