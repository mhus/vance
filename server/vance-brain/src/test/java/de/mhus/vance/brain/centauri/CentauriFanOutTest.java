package de.mhus.vance.brain.centauri;

import static de.mhus.vance.brain.centauri.FakeFeedSource.item;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.agrajag.AgrajagChecker;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.toolpack.feed.FeedDirection;
import de.mhus.vance.toolpack.feed.FeedException;
import de.mhus.vance.toolpack.feed.FeedFilter;
import de.mhus.vance.toolpack.feed.FeedItem;
import de.mhus.vance.toolpack.feed.FeedPage;
import de.mhus.vance.toolpack.feed.FeedScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.json.JsonMapper;

/**
 * The seam between the dispatcher and the merge — what the fan-out
 * <b>hands over</b>, rather than what the merge then does with it.
 *
 * <p>{@link FeedMergerTest} covers the merge itself thoroughly, and
 * {@link CentauriServiceTest} covers resolution and gating. Neither could see
 * the shape of failure that lives exactly in between: a source that does not
 * answer is, on the way into the merge, indistinguishable from a source that
 * has nothing and from one the reader deleted. Every case here is that same
 * confusion in a different disguise.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CentauriFanOutTest {

    private static final FeedScope SCOPE = new FeedScope("acme", "proj", null, "marvin");
    private static final FeedStream ALPHA = new FeedStream("alpha", "world");
    private static final FeedStream BETA = new FeedStream("beta", "tech");
    private static final FeedStream GAMMA = new FeedStream("gamma", "science");

    /** Short enough that a hanging source exhausts it inside a unit test. */
    private static final Duration BUDGET = Duration.ofMillis(300);

    @Mock
    private FeedSourceFactory factory;
    @Mock
    private CentauriGateService gate;
    @Mock
    private FeedActorResolver actorResolver;
    @Mock
    private AgrajagChecker agrajagChecker;

    private final CentauriCursorCodec codec = new CentauriCursorCodec(JsonMapper.builder().build());
    private CentauriService service;

    @BeforeEach
    void setUp() {
        service = new CentauriService(
                factory,
                new FeedCapabilitiesCache(),
                gate,
                actorResolver,
                codec,
                agrajagChecker,
                new MetricService(new SimpleMeterRegistry()),
                BUDGET);
        when(gate.check(any(), any())).thenReturn(Optional.empty());
        when(actorResolver.resolve(any(), any())).thenReturn(null);
    }

    @Test
    void fetchPage_oneSlowSource_stillRendersWhatTheOthersAlreadyDelivered() {
        // Every task starts at the same moment, so by the time the slow one has
        // burnt the whole budget the fast ones have long since finished and
        // their pages are sitting in their futures. Reading the spent budget as
        // "these timed out too" threw those pages away and left the reader an
        // empty page with three timeout notes — on every single page, for as
        // long as one source is slow, which is the case the budget exists for.
        when(factory.find(any(), eq("alpha"))).thenReturn(new FakeFeedSource("alpha")
                .answeringAfter(Duration.ofSeconds(30))
                .serving(page(item("a1", "2026-08-19T10:00:00Z", "https://a.test/1"))));
        source("beta", page(item("b1", "2026-08-19T09:00:00Z", "https://b.test/1")));
        source("gamma", page(item("g1", "2026-08-19T08:00:00Z", "https://g.test/1")));

        CentauriPage result = service.fetchPage(request(ALPHA, BETA, GAMMA), SCOPE);

        assertThat(result.items()).extracting(i -> i.item().id()).containsExactly("b1", "g1");
        assertThat(result.notes()).singleElement()
                .satisfies(n -> {
                    assertThat(n.sourceId()).isEqualTo("alpha");
                    assertThat(n.kind()).isEqualTo(CentauriNote.Kind.TIMED_OUT);
                });
    }

    @Test
    void fetchPage_streamTimedOut_keepsItsCursorAndDoesNotEndTheScroll() {
        // "Did not answer" is not "was removed from the feed", and only the
        // second may drop a cursor. Dropping it restarted that source at its
        // newest entry on the next page: the reader, four pages deep, saw the
        // top of alpha again and scrolled it a second time.
        when(factory.find(any(), eq("alpha"))).thenReturn(new FakeFeedSource("alpha")
                .answeringAfter(Duration.ofSeconds(30)));
        source("beta", new FeedPage(
                List.of(item("b1", "2026-08-19T09:00:00Z", "https://b.test/1")), null, false));

        CentauriPage result = service.fetchPage(
                requestWith(cursor(Map.of(ALPHA.key(), "a-page-4", BETA.key(), "b-page-4")),
                        ALPHA, BETA),
                SCOPE);

        CentauriCursor next = codec.decode(result.nextCursor());
        assertThat(next.perStream()).containsEntry(ALPHA.key(), "a-page-4");
        assertThat(next.exhausted()).doesNotContain(ALPHA.key());
        assertThat(result.hasMore()).isTrue();
    }

    @Test
    void fetchPage_everySourceFailed_keepsTheCursorAndKeepsTheScrollOpen() {
        // A DNS blip takes out both sources for one round. Answering with no
        // cursor and hasMore=false ends the endless scroll, and the next
        // "load more" starts the whole feed at its top — the reader loses the
        // position they scrolled to and nothing in the answer says so.
        when(factory.find(any(), eq("alpha"))).thenReturn(
                new FakeFeedSource("alpha").failingWith(new FeedException("upstream 503")));
        when(factory.find(any(), eq("beta"))).thenReturn(
                new FakeFeedSource("beta").failingWith(new FeedException("connection refused")));

        CentauriPage result = service.fetchPage(
                requestWith(cursor(Map.of(ALPHA.key(), "a-page-4", BETA.key(), "b-page-4")),
                        ALPHA, BETA),
                SCOPE);

        assertThat(result.items()).isEmpty();
        assertThat(result.hasMore()).isTrue();
        CentauriCursor next = codec.decode(result.nextCursor());
        assertThat(next.perStream())
                .containsEntry(ALPHA.key(), "a-page-4")
                .containsEntry(BETA.key(), "b-page-4");
        assertThat(result.notes()).hasSize(2)
                .allSatisfy(n -> assertThat(n.kind()).isEqualTo(CentauriNote.Kind.FAILED));
    }

    @Test
    void fetchPage_coolingDownSource_keepsItsCursorTooAlthoughItWasNeverAsked() {
        // A cooldown lasts minutes, so this is the case that hits every page
        // rather than one. It is still an outage: the source may well have
        // entries, we are simply not asking right now.
        source("alpha", page(item("a1", "2026-08-19T10:00:00Z", "https://a.test/1")));
        source("beta");
        when(gate.check(any(), eq("beta")))
                .thenReturn(Optional.of(CentauriGateService.Blocked.COOLING_DOWN));

        CentauriPage result = service.fetchPage(
                requestWith(cursor(Map.of(BETA.key(), "b-page-4")), ALPHA, BETA), SCOPE);

        CentauriCursor next = codec.decode(result.nextCursor());
        assertThat(next.perStream()).containsEntry(BETA.key(), "b-page-4");
        assertThat(result.hasMore()).isTrue();
    }

    @Test
    void fetchPage_disabledSource_maySettleTheRound() {
        // The counterpart, and the reason the distinction is two-valued rather
        // than "did we get a page": a switched-off source is an answer. If it
        // kept hasMore alive, a feed with one disabled stream would never
        // reach its end.
        source("alpha", new FeedPage(
                List.of(item("a1", "2026-08-19T10:00:00Z", "https://a.test/1")), null, false));
        source("beta");
        when(gate.check(any(), eq("beta")))
                .thenReturn(Optional.of(CentauriGateService.Blocked.DISABLED));

        CentauriPage result = service.fetchPage(
                requestWith(cursor(Map.of(BETA.key(), "b-page-4")), ALPHA, BETA), SCOPE);

        // The cursor still survives — the source is configured, just off.
        assertThat(codec.decode(result.nextCursor()).perStream())
                .containsEntry(BETA.key(), "b-page-4");
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void fetchPage_unreachableCapabilities_isAFailureRatherThanAMissingFacet() {
        // Falling back to the pessimistic declaration turned an outage into a
        // statement: with a facet filter active the reader was told "this
        // source does not offer that dimension" — which the source never said —
        // the failure tracker never saw it, and a permanently broken
        // capabilities endpoint stayed invisible for as long as the filter
        // stood.
        source("alpha", page(item("a1", "2026-08-19T10:00:00Z", "https://a.test/1")));
        when(factory.find(any(), eq("beta"))).thenReturn(new FakeFeedSource("beta")
                .failingCapabilitiesWith(new FeedException("capabilities: 502")));

        CentauriPage result = service.fetchPage(new CentauriPageRequest(
                List.of(ALPHA, BETA),
                new FeedFilter(null, Set.of(), List.of(), List.of(), null,
                        Map.of("origin-place", List.of("m49:142"))),
                10, FeedDirection.OLDER, null), SCOPE);

        assertThat(result.notes())
                .filteredOn(n -> "beta".equals(n.sourceId()))
                .singleElement()
                .satisfies(n -> assertThat(n.kind()).isEqualTo(CentauriNote.Kind.FAILED));
        verify(agrajagChecker, times(1))
                .handle(eq(CentauriSettings.cooldownSubject("beta")), any(), any());
    }

    @Test
    void fetchPage_theSameStreamTwice_isFetchedOnce() {
        // A duplicated (source, selector) costs the source a second identical
        // request and makes every one of its entries look like a duplicate of
        // itself in the merge.
        source("alpha", page(item("a1", "2026-08-19T10:00:00Z", "https://a.test/1")));

        CentauriPage result = service.fetchPage(
                request(ALPHA, new FeedStream("alpha", "world")), SCOPE);

        FakeFeedSource alpha = (FakeFeedSource) factory.find(SCOPE, "alpha");
        assertThat(alpha.received()).hasSize(1);
        assertThat(result.items()).hasSize(1);
        assertThat(result.droppedAsDuplicate()).isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────

    private void source(String id, FeedPage... pages) {
        when(factory.find(any(), eq(id))).thenReturn(new FakeFeedSource(id).serving(pages));
    }

    private static FeedPage page(FeedItem... items) {
        return new FeedPage(List.of(items), null, true);
    }

    private String cursor(Map<String, String> perStream) {
        return codec.encode(new CentauriCursor(perStream, null, Set.of()));
    }

    private static CentauriPageRequest request(FeedStream... streams) {
        return CentauriPageRequest.of(List.of(streams));
    }

    private static CentauriPageRequest requestWith(String cursor, FeedStream... streams) {
        return new CentauriPageRequest(
                List.of(streams), FeedFilter.none(), 20, FeedDirection.OLDER, cursor);
    }
}
