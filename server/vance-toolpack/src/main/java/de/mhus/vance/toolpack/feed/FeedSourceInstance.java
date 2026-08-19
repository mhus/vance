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
     * <p>The default — the item id — is right for every source whose
     * paging is id-based, which includes Mastodon's {@code max_id} and the
     * ode contract. A source that pages by timestamp or opaque token
     * overrides it.
     */
    default String cursorAfter(FeedItem item) {
        return item.id();
    }

    /**
     * Load the full body of an entry whose list representation was only a
     * teaser. Sources that set {@link FeedCapabilities#carriesFullBody()}
     * never need this.
     */
    default Optional<String> loadBody(String itemId, @org.jspecify.annotations.Nullable FeedActor actor) {
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
