package de.mhus.vance.brain.centauri;

import de.mhus.vance.toolpack.feed.FeedCapabilities;
import de.mhus.vance.toolpack.feed.FeedFetch;
import de.mhus.vance.toolpack.feed.FeedItem;
import de.mhus.vance.toolpack.feed.FeedPage;
import de.mhus.vance.toolpack.feed.FeedSelectorKind;
import de.mhus.vance.toolpack.feed.FeedSelectorMode;
import de.mhus.vance.toolpack.feed.FeedSourceInstance;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Scriptable in-memory source for the Centauri tests. Test scope only — the
 * production tree has no stub sources.
 */
final class FakeFeedSource implements FeedSourceInstance {

    private final String id;
    private final Deque<FeedPage> scripted = new ArrayDeque<>();
    private final List<FeedFetch> received = new ArrayList<>();

    private FeedCapabilities capabilities = new FeedCapabilities(
            FeedSelectorMode.ENUMERABLE, Set.of(FeedSelectorKind.CATEGORY),
            false, false, false, false, true,
            40, Set.of(), false, Duration.ofMinutes(30));

    private @Nullable RuntimeException failure;
    private @Nullable RuntimeException signalFailure;
    private final List<de.mhus.vance.toolpack.feed.FeedSignalRequest> signals = new ArrayList<>();

    FakeFeedSource(String id) {
        this.id = id;
    }

    // ── scripting ────────────────────────────────────────────────────

    FakeFeedSource serving(FeedPage... pages) {
        scripted.addAll(List.of(pages));
        return this;
    }

    FakeFeedSource withCapabilities(FeedCapabilities caps) {
        this.capabilities = caps;
        return this;
    }

    FakeFeedSource failingWith(RuntimeException e) {
        this.failure = e;
        return this;
    }

    FakeFeedSource failingSignalWith(RuntimeException e) {
        this.signalFailure = e;
        return this;
    }

    /** What actually reached the source — the dispatcher must not send more. */
    List<de.mhus.vance.toolpack.feed.FeedSignalRequest> signals() {
        return List.copyOf(signals);
    }

    List<FeedFetch> received() {
        return List.copyOf(received);
    }

    // ── helpers used by the tests ────────────────────────────────────

    static FeedItem item(String id, String isoInstant, String url) {
        return new FeedItem(id, /* cursor */ null, Instant.parse(isoInstant), "title-" + id, url,
                null, null, null, null, null, null, List.of(), Map.of());
    }

    static FeedItem item(String id, String isoInstant, String url, String title) {
        return new FeedItem(id, /* cursor */ null, Instant.parse(isoInstant), title, url,
                null, null, null, null, null, null, List.of(), Map.of());
    }

    static FeedItem item(
            String id, String isoInstant, String url, String title, @Nullable String language) {
        return new FeedItem(id, /* cursor */ null, Instant.parse(isoInstant), title, url,
                null, null, null, language, null, null, List.of(), Map.of());
    }

    /**
     * An entry carrying the source's own resume token — the case a source paging
     * by (publishedAt, id) has, where a bare item id is not a usable cursor.
     */
    static FeedItem itemWithCursor(String id, String isoInstant, String url, String cursor) {
        return new FeedItem(id, cursor, Instant.parse(isoInstant), "title-" + id, url,
                null, null, null, null, null, null, List.of(), Map.of());
    }

    // ── FeedSourceInstance ───────────────────────────────────────────

    @Override
    public String id() {
        return id;
    }

    @Override
    public String displayName() {
        return "Fake " + id;
    }

    @Override
    public String baseUrl() {
        return "https://" + id + ".test/";
    }

    @Override
    public FeedCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public List<de.mhus.vance.toolpack.feed.FeedSelector> listSelectors() {
        return List.of();
    }

    @Override
    public de.mhus.vance.toolpack.feed.FeedSignalOutcome sendSignal(
            de.mhus.vance.toolpack.feed.FeedSignalRequest request) {
        if (signalFailure != null) {
            throw signalFailure;
        }
        signals.add(request);
        return de.mhus.vance.toolpack.feed.FeedSignalOutcome.ACCEPTED;
    }

    @Override
    public FeedPage fetch(FeedFetch request) {
        received.add(request);
        if (failure != null) {
            throw failure;
        }
        FeedPage next = scripted.poll();
        return next == null ? FeedPage.empty() : next;
    }
}
