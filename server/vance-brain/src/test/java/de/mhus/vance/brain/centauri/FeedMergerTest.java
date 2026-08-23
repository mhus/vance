package de.mhus.vance.brain.centauri;

import static de.mhus.vance.brain.centauri.FakeFeedSource.item;
import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.toolpack.feed.FeedContentPolicy;
import de.mhus.vance.toolpack.feed.FeedDirection;
import de.mhus.vance.toolpack.feed.FeedFilter;
import de.mhus.vance.toolpack.feed.FeedItem;
import de.mhus.vance.toolpack.feed.FeedPage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The merge is where Centauri can be wrong without anyone noticing, so the
 * cases here are the failure modes rather than the happy path: unstable order
 * at page boundaries, a cursor that does not move, a cursor that moves too far.
 */
class FeedMergerTest {

    private static final FeedStream ALPHA = new FeedStream("alpha", "world");
    private static final FeedStream BETA = new FeedStream("beta", "tech");

    @Test
    void merge_twoStreams_interleavesNewestFirst() {
        FakeFeedSource alpha = new FakeFeedSource("alpha");
        FakeFeedSource beta = new FakeFeedSource("beta");

        var result = merge(
                List.of(
                        fetch(ALPHA, alpha, page(false, null,
                                item("a1", "2026-08-19T10:00:00Z", "https://a.test/1"),
                                item("a2", "2026-08-19T08:00:00Z", "https://a.test/2"))),
                        fetch(BETA, beta, page(false, null,
                                item("b1", "2026-08-19T09:00:00Z", "https://b.test/1")))),
                FeedFilter.none(), 10, FeedDirection.OLDER, CentauriCursor.fresh());

        assertThat(result.items()).extracting(i -> i.item().id())
                .containsExactly("a1", "b1", "a2");
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void merge_identicalTimestamps_ordersByStreamThenId() {
        FakeFeedSource alpha = new FakeFeedSource("alpha");
        FakeFeedSource beta = new FakeFeedSource("beta");
        String sameMoment = "2026-08-19T10:00:00Z";

        var result = merge(
                List.of(
                        fetch(BETA, beta, page(false, null,
                                item("b1", sameMoment, "https://b.test/1"))),
                        fetch(ALPHA, alpha, page(false, null,
                                item("a2", sameMoment, "https://a.test/2"),
                                item("a1", sameMoment, "https://a.test/1")))),
                FeedFilter.none(), 10, FeedDirection.OLDER, CentauriCursor.fresh());

        // alpha|world sorts before beta|tech, and within alpha a1 before a2 —
        // the order must not depend on which stream answered first.
        assertThat(result.items()).extracting(i -> i.item().id())
                .containsExactly("a1", "a2", "b1");
    }

    @Test
    void merge_pageSmallerThanFetch_advancesCursorOnlyToDeliveredItem() {
        FakeFeedSource alpha = new FakeFeedSource("alpha");

        var result = merge(
                List.of(fetch(ALPHA, alpha, page(true, "page-end",
                        item("a1", "2026-08-19T10:00:00Z", "https://a.test/1"),
                        item("a2", "2026-08-19T09:00:00Z", "https://a.test/2"),
                        item("a3", "2026-08-19T08:00:00Z", "https://a.test/3")))),
                FeedFilter.none(), 1, FeedDirection.OLDER, CentauriCursor.fresh());

        assertThat(result.items()).hasSize(1);
        // Not "page-end": the other two entries were never shown and must be
        // fetched again, otherwise the scroll skips them.
        assertThat(result.cursor().perStream()).containsEntry(ALPHA.key(), "a1");
        assertThat(result.hasMore()).isTrue();
        assertThat(result.cursor().exhausted()).isEmpty();
    }

    @Test
    void merge_everythingFilteredOut_stillAdvancesSoTheScrollProgresses() {
        FakeFeedSource alpha = new FakeFeedSource("alpha");
        FeedFilter excludeAll = new FeedFilter(
                null, Set.of(), List.of(), List.of("advert"), null);

        var result = merge(
                List.of(fetch(ALPHA, alpha, page(true, "page-end",
                        item("a1", "2026-08-19T10:00:00Z", "https://a.test/1", "an advert"),
                        item("a2", "2026-08-19T09:00:00Z", "https://a.test/2", "another advert")))),
                excludeAll, 10, FeedDirection.OLDER, CentauriCursor.fresh());

        assertThat(result.items()).isEmpty();
        assertThat(result.droppedByFilter()).isEqualTo(2);
        // The whole fetched page was decided about, so the source's own page-end
        // cursor is correct. Standing still here would re-fetch and re-reject
        // the same entries forever.
        assertThat(result.cursor().perStream()).containsEntry(ALPHA.key(), "page-end");
        assertThat(result.hasMore()).isTrue();
    }

    @Test
    void merge_emptyPageWithMoreToCome_advancesViaPageCursor() {
        FakeFeedSource alpha = new FakeFeedSource("alpha");

        var result = merge(
                List.of(fetch(ALPHA, alpha, new FeedPage(List.of(), "page-end", true))),
                FeedFilter.none(), 10, FeedDirection.OLDER, CentauriCursor.fresh());

        assertThat(result.items()).isEmpty();
        assertThat(result.hasMore()).isTrue();
        assertThat(result.cursor().perStream()).containsEntry(ALPHA.key(), "page-end");
    }

    @Test
    void merge_emptyPageClaimingMoreWithNoCursor_retiresTheStream() {
        FakeFeedSource alpha = new FakeFeedSource("alpha");

        var result = merge(
                // hasMore with nothing delivered and nothing to resume from: the
                // next request would be identical, so believing it would spin the
                // scroll forever.
                List.of(fetch(ALPHA, alpha, new FeedPage(List.of(), null, true))),
                FeedFilter.none(), 10, FeedDirection.OLDER, CentauriCursor.fresh());

        assertThat(result.items()).isEmpty();
        assertThat(result.cursor().exhausted()).contains(ALPHA.key());
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void merge_midPageCut_usesTheSourcesOwnPerItemCursor() {
        FakeFeedSource alpha = new FakeFeedSource("alpha");

        var result = merge(
                List.of(fetch(ALPHA, alpha, page(true, "page-end",
                        FakeFeedSource.itemWithCursor(
                                "a1", "2026-08-19T10:00:00Z", "https://a.test/1", "ts-10|a1"),
                        FakeFeedSource.itemWithCursor(
                                "a2", "2026-08-19T09:00:00Z", "https://a.test/2", "ts-09|a2")))),
                FeedFilter.none(), 1, FeedDirection.OLDER, CentauriCursor.fresh());

        // The token the source supplied, not the bare id. A source paging by
        // (publishedAt, id) cannot resume from an id, and it fails silently:
        // it reads one as "start from the top" and the scroll repeats.
        assertThat(result.cursor().perStream()).containsEntry(ALPHA.key(), "ts-10|a1");
    }

    @Test
    void merge_textAppliedBySource_isNotRejectedByThePostFilter() {
        FakeFeedSource alpha = new FakeFeedSource("alpha");
        FeedFilter filter = new FeedFilter("tariffs", Set.of(), List.of(), List.of(), null);

        var result = merge(
                // The source matched on text it does not deliver — a translated
                // entry, indexed by its original words. Re-checking locally used
                // to drop a hit the source had found correctly.
                List.of(fetch(ALPHA, alpha, page(false, null,
                                item("a1", "2026-08-19T10:00:00Z", "https://a.test/1",
                                        "Zoelle auf Stahl")),
                        filter)),
                filter, 10, FeedDirection.OLDER, CentauriCursor.fresh());

        assertThat(result.items()).extracting(i -> i.item().id()).containsExactly("a1");
        assertThat(result.droppedByFilter()).isZero();
    }

    @Test
    void merge_exhaustedStream_isMarkedAndStaysMarked() {
        FakeFeedSource beta = new FakeFeedSource("beta");
        CentauriCursor incoming = new CentauriCursor(
                Map.of(ALPHA.key(), "a9"), Instant.parse("2026-08-19T07:00:00Z"),
                Set.of(ALPHA.key()));

        var result = merge(
                List.of(fetch(BETA, beta, page(false, null,
                        item("b1", "2026-08-19T06:00:00Z", "https://b.test/1")))),
                FeedFilter.none(), 10, FeedDirection.OLDER, incoming);

        assertThat(result.cursor().exhausted()).contains(ALPHA.key(), BETA.key());
        assertThat(result.cursor().perStream()).containsEntry(ALPHA.key(), "a9");
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void merge_sameStoryFromTwoStreams_isDeliveredOnce() {
        FakeFeedSource alpha = new FakeFeedSource("alpha");
        FakeFeedSource beta = new FakeFeedSource("beta");

        var result = merge(
                List.of(
                        fetch(ALPHA, alpha, page(false, null,
                                item("a1", "2026-08-19T10:00:00Z",
                                        "https://news.test/story?utm_source=alpha"))),
                        fetch(BETA, beta, page(false, null,
                                item("b1", "2026-08-19T09:00:00Z",
                                        "https://www.news.test/story/")))),
                FeedFilter.none(), 10, FeedDirection.OLDER, CentauriCursor.fresh());

        assertThat(result.items()).hasSize(1);
        assertThat(result.droppedAsDuplicate()).isEqualTo(1);
    }

    @Test
    void merge_streamRemovedFromConfiguration_dropsItsCursor() {
        FakeFeedSource beta = new FakeFeedSource("beta");
        CentauriCursor incoming = new CentauriCursor(
                Map.of("gone|somewhere", "g1", BETA.key(), "b0"), null, Set.of());

        var result = merge(
                List.of(fetch(BETA, beta, page(false, null,
                        item("b1", "2026-08-19T09:00:00Z", "https://b.test/1")))),
                FeedFilter.none(), 10, FeedDirection.OLDER, incoming);

        assertThat(result.cursor().perStream()).doesNotContainKey("gone|somewhere");
    }

    @Test
    void merge_newerDirection_ordersOldestFirst() {
        FakeFeedSource alpha = new FakeFeedSource("alpha");

        var result = merge(
                List.of(fetch(ALPHA, alpha, page(false, null,
                        item("a1", "2026-08-19T10:00:00Z", "https://a.test/1"),
                        item("a2", "2026-08-19T08:00:00Z", "https://a.test/2")))),
                FeedFilter.none(), 10, FeedDirection.NEWER, CentauriCursor.fresh());

        assertThat(result.items()).extracting(i -> i.item().id())
                .containsExactly("a2", "a1");
    }

    @Test
    void merge_unsortedPage_isSortedRatherThanTrusted() {
        FakeFeedSource alpha = new FakeFeedSource("alpha");

        var result = merge(
                List.of(fetch(ALPHA, alpha, page(true, null,
                        item("a2", "2026-08-19T08:00:00Z", "https://a.test/2"),
                        item("a1", "2026-08-19T10:00:00Z", "https://a.test/1")))),
                FeedFilter.none(), 1, FeedDirection.OLDER, CentauriCursor.fresh());

        // A source that violates the ordering contract may lose ordering
        // quality, but it must not be able to corrupt the cursor.
        assertThat(result.items()).extracting(i -> i.item().id()).containsExactly("a1");
        assertThat(result.cursor().perStream()).containsEntry(ALPHA.key(), "a1");
    }

    @Test
    void fetchLimit_overFetchesOnlyWhenPostFilteringIsNeeded() {
        assertThat(FeedMerger.fetchLimit(20, false, 40)).isEqualTo(20);
        assertThat(FeedMerger.fetchLimit(20, true, 100)).isEqualTo(60);
        assertThat(FeedMerger.fetchLimit(20, true, 40)).isEqualTo(40);
        assertThat(FeedMerger.fetchLimit(20, true, 1)).isEqualTo(1);
    }

    @Test
    void normalizeUrl_ignoresTrackingAndHostCosmetics() {
        assertThat(FeedMerger.normalizeUrl("https://www.News.test/a/?utm_campaign=x"))
                .isEqualTo(FeedMerger.normalizeUrl("https://news.test/a"));
        assertThat(FeedMerger.normalizeUrl("https://news.test/a?id=7"))
                .isNotEqualTo(FeedMerger.normalizeUrl("https://news.test/a?id=8"));
    }

    // ── silence: the streams that answered nothing ───────────────────

    @Test
    void merge_streamSilentThisRound_keepsItsCursorAndKeepsTheScrollOpen() {
        // A source that timed out or sits in a cooldown has not said "I am
        // done" and has not been removed either. Dropping its cursor restarts
        // it at its newest entry on the next page — the reader would see the
        // same handful of B entries again in the middle of a scroll.
        FakeFeedSource beta = new FakeFeedSource("beta");
        CentauriCursor incoming = new CentauriCursor(
                Map.of(ALPHA.key(), "a-page-4", BETA.key(), "b-page-4"), null, Set.of());

        var result = FeedMerger.merge(
                List.of(fetch(BETA, beta, page(false, null,
                        item("b1", "2026-08-19T09:00:00Z", "https://b.test/1")))),
                List.of(FeedMerger.StreamSilence.unresolved(ALPHA)),
                FeedFilter.none(), 10, FeedDirection.OLDER, incoming);

        assertThat(result.cursor().perStream()).containsEntry(ALPHA.key(), "a-page-4");
        assertThat(result.cursor().exhausted()).doesNotContain(ALPHA.key());
        // beta said it is finished, alpha said nothing — so the page is not
        // the end of the feed.
        assertThat(result.hasMore()).isTrue();
    }

    @Test
    void merge_streamSettledThisRound_keepsItsCursorButMayEndTheRound() {
        // "Switched off" and "does not declare that facet" are answers. The
        // cursor survives because the stream is still configured, but the
        // scroll is allowed to end on it — otherwise a disabled source keeps
        // an endless scroll alive forever with nothing to add.
        FakeFeedSource beta = new FakeFeedSource("beta");
        CentauriCursor incoming = new CentauriCursor(
                Map.of(ALPHA.key(), "a-page-4"), null, Set.of());

        var result = FeedMerger.merge(
                List.of(fetch(BETA, beta, page(false, null,
                        item("b1", "2026-08-19T09:00:00Z", "https://b.test/1")))),
                List.of(FeedMerger.StreamSilence.settled(ALPHA)),
                FeedFilter.none(), 10, FeedDirection.OLDER, incoming);

        assertThat(result.cursor().perStream()).containsEntry(ALPHA.key(), "a-page-4");
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void merge_everyStreamSilent_carriesTheWholeCursorThrough() {
        // A network blip that takes out every source at once. Answering with a
        // fresh cursor would restart the feed at its top on the next "load
        // more" — the reader loses the position they scrolled to, and nothing
        // in the response says that happened.
        CentauriCursor incoming = new CentauriCursor(
                Map.of(ALPHA.key(), "a-page-4", BETA.key(), "b-page-4"),
                Instant.parse("2026-08-19T07:00:00Z"), Set.of());

        var result = FeedMerger.merge(
                List.of(),
                List.of(FeedMerger.StreamSilence.unresolved(ALPHA),
                        FeedMerger.StreamSilence.unresolved(BETA)),
                FeedFilter.none(), 10, FeedDirection.OLDER, incoming);

        assertThat(result.items()).isEmpty();
        assertThat(result.cursor().perStream())
                .containsEntry(ALPHA.key(), "a-page-4")
                .containsEntry(BETA.key(), "b-page-4");
        assertThat(result.cursor().watermark())
                .isEqualTo(Instant.parse("2026-08-19T07:00:00Z"));
        assertThat(result.hasMore()).isTrue();
    }

    @Test
    void merge_watermark_namesTheLastDeliveredEntryNotTheCut() {
        // Spec §5.1: the watermark is the publishedAt of the last *delivered*
        // entry. The page below ends on a rejected one, so cut and delivered
        // differ — and a watermark describing an entry nobody saw is a field
        // that disagrees with its own definition.
        FakeFeedSource alpha = new FakeFeedSource("alpha");
        FeedFilter excludeAdverts = new FeedFilter(
                null, Set.of(), List.of(), List.of("advert"), null);

        var result = merge(
                List.of(fetch(ALPHA, alpha, page(true, "page-end",
                        item("a1", "2026-08-19T10:00:00Z", "https://a.test/1", "a story"),
                        item("a2", "2026-08-19T09:00:00Z", "https://a.test/2", "an advert")))),
                excludeAdverts, 10, FeedDirection.OLDER, CentauriCursor.fresh());

        assertThat(result.items()).extracting(i -> i.item().id()).containsExactly("a1");
        assertThat(result.cursor().watermark())
                .isEqualTo(Instant.parse("2026-08-19T10:00:00Z"));
    }

    // ── helpers ──────────────────────────────────────────────────────

    // ──── Content policy ────────────────────────────────────────────────

    /**
     * The source's standing policy drops entries the reader never asked about —
     * and, like a filter rejection, the cursor still moves past them. Otherwise
     * a stream of nothing but blocked entries would re-fetch the same page
     * forever and the scroll would never progress.
     */
    @Test
    void merge_policyBlockedEntries_areDroppedButStillAdvanceTheCursor() {
        FakeFeedSource alpha = new FakeFeedSource("alpha").withContentPolicy(
                new FeedContentPolicy(false, Set.of("blocked.test"), Set.of()));

        var result = merge(
                List.of(fetch(ALPHA, alpha, page(true, "page-end",
                        item("a1", "2026-08-19T10:00:00Z", "https://blocked.test/1"),
                        item("a2", "2026-08-19T09:00:00Z", "https://blocked.test/2")))),
                FeedFilter.none(), 10, FeedDirection.OLDER, CentauriCursor.fresh());

        assertThat(result.items()).isEmpty();
        assertThat(result.cursor().perStream()).containsEntry(ALPHA.key(), "page-end");
    }

    /** The policy is the source's, so it applies to that stream only. */
    @Test
    void merge_policyOfOneStream_leavesTheOtherAlone() {
        FakeFeedSource alpha = new FakeFeedSource("alpha").withContentPolicy(
                new FeedContentPolicy(false, Set.of("a.test"), Set.of()));
        FakeFeedSource beta = new FakeFeedSource("beta");

        var result = merge(
                List.of(
                        fetch(ALPHA, alpha, page(false, null,
                                item("a1", "2026-08-19T10:00:00Z", "https://a.test/1"))),
                        fetch(BETA, beta, page(false, null,
                                item("b1", "2026-08-19T09:00:00Z", "https://b.test/1")))),
                FeedFilter.none(), 10, FeedDirection.OLDER, CentauriCursor.fresh());

        assertThat(result.items()).extracting(i -> i.item().id()).containsExactly("b1");
    }

    /** A source without policy keeps behaving exactly as before. */
    @Test
    void merge_withoutPolicy_deliversEverything() {
        FakeFeedSource alpha = new FakeFeedSource("alpha");

        var result = merge(
                List.of(fetch(ALPHA, alpha, page(false, null,
                        item("a1", "2026-08-19T10:00:00Z", "https://anything.test/1")))),
                FeedFilter.none(), 10, FeedDirection.OLDER, CentauriCursor.fresh());

        assertThat(result.items()).hasSize(1);
    }

    /** Every stream answered — the shape most of these cases are about. */
    private static FeedMerger.MergeResult merge(
            List<FeedMerger.StreamFetch> fetches, FeedFilter filter, int pageSize,
            FeedDirection direction, CentauriCursor incoming) {
        return FeedMerger.merge(fetches, List.of(), filter, pageSize, direction, incoming);
    }

    private static FeedMerger.StreamFetch fetch(
            FeedStream stream, FakeFeedSource source, FeedPage page) {
        return new FeedMerger.StreamFetch(stream, source, page, FeedFilter.none());
    }

    /** A fetch where the source had already applied part of the filter. */
    private static FeedMerger.StreamFetch fetch(
            FeedStream stream, FakeFeedSource source, FeedPage page, FeedFilter pushdown) {
        return new FeedMerger.StreamFetch(stream, source, page, pushdown);
    }

    private static FeedPage page(boolean hasMore, String nextCursor, FeedItem... items) {
        return new FeedPage(List.of(items), nextCursor, hasMore);
    }
}