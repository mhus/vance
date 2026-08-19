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

    List<FeedFetch> received() {
        return List.copyOf(received);
    }

    // ── helpers used by the tests ────────────────────────────────────

    static FeedItem item(String id, String isoInstant, String url) {
        return new FeedItem(id, Instant.parse(isoInstant), "title-" + id, url,
                null, null, null, null, null, null, List.of(), Map.of());
    }

    static FeedItem item(String id, String isoInstant, String url, String title) {
        return new FeedItem(id, Instant.parse(isoInstant), title, url,
                null, null, null, null, null, null, List.of(), Map.of());
    }

    static FeedItem item(
            String id, String isoInstant, String url, String title, @Nullable String language) {
        return new FeedItem(id, Instant.parse(isoInstant), title, url,
                null, null, null, language, null, null, List.of(), Map.of());
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
    public FeedPage fetch(FeedFetch request) {
        received.add(request);
        if (failure != null) {
            throw failure;
        }
        FeedPage next = scripted.poll();
        return next == null ? FeedPage.empty() : next;
    }
}
