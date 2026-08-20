package de.mhus.vance.brain.centauri;

import static de.mhus.vance.brain.centauri.FakeFeedSource.item;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import de.mhus.vance.toolpack.feed.FeedPage;
import de.mhus.vance.toolpack.feed.FeedScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
 * The dispatcher's job beyond delegating: keep a mixed feed readable when
 * individual sources are missing, switched off or broken.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CentauriServiceTest {

    private static final FeedScope SCOPE = new FeedScope("acme", "proj", null, "marvin");
    private static final FeedStream ALPHA = new FeedStream("alpha", "world");
    private static final FeedStream BETA = new FeedStream("beta", "tech");

    @Mock
    private FeedSourceFactory factory;
    @Mock
    private CentauriGateService gate;
    @Mock
    private FeedActorResolver actorResolver;
    @Mock
    private AgrajagChecker agrajagChecker;

    private CentauriService service;

    @BeforeEach
    void setUp() {
        service = new CentauriService(
                factory,
                new FeedCapabilitiesCache(),
                gate,
                actorResolver,
                new CentauriCursorCodec(JsonMapper.builder().build()),
                agrajagChecker,
                new MetricService(new SimpleMeterRegistry()));
        when(gate.check(any(), any())).thenReturn(Optional.empty());
        when(actorResolver.resolve(any(), any())).thenReturn(null);
    }

    @Test
    void fetchPage_mergesConfiguredStreams() {
        source("alpha", page(
                item("a1", "2026-08-19T10:00:00Z", "https://a.test/1")));
        source("beta", page(
                item("b1", "2026-08-19T09:00:00Z", "https://b.test/1")));

        CentauriPage result = service.fetchPage(request(ALPHA, BETA), SCOPE);

        assertThat(result.items()).extracting(i -> i.item().id()).containsExactly("a1", "b1");
        assertThat(result.items()).extracting(CentauriItem::sourceDisplayName)
                .containsExactly("Fake alpha", "Fake beta");
        assertThat(result.nextCursor()).isNotBlank();
        assertThat(result.notes()).isEmpty();
    }

    @Test
    void fetchPage_unknownSource_isANoteAndTheRestStillRenders() {
        source("alpha", page(item("a1", "2026-08-19T10:00:00Z", "https://a.test/1")));
        when(factory.find(any(), eq("gone"))).thenReturn(null);

        CentauriPage result = service.fetchPage(
                request(ALPHA, new FeedStream("gone", "somewhere")), SCOPE);

        assertThat(result.items()).hasSize(1);
        assertThat(result.notes()).singleElement()
                .satisfies(note -> {
                    assertThat(note.sourceId()).isEqualTo("gone");
                    assertThat(note.kind()).isEqualTo(CentauriNote.Kind.UNKNOWN_SOURCE);
                });
    }

    @Test
    void fetchPage_disabledSource_isANoteRatherThanSilentAbsence() {
        source("alpha", page(item("a1", "2026-08-19T10:00:00Z", "https://a.test/1")));
        source("beta", page(item("b1", "2026-08-19T09:00:00Z", "https://b.test/1")));
        when(gate.check(any(), eq("beta")))
                .thenReturn(Optional.of(CentauriGateService.Blocked.DISABLED));

        CentauriPage result = service.fetchPage(request(ALPHA, BETA), SCOPE);

        assertThat(result.items()).hasSize(1);
        // A page that just omits a source looks like a source with no news,
        // which is a different statement entirely.
        assertThat(result.notes()).singleElement()
                .satisfies(n -> assertThat(n.kind()).isEqualTo(CentauriNote.Kind.DISABLED));
    }

    @Test
    void fetchPage_failingSource_doesNotTakeTheFeedDown() {
        source("alpha", page(item("a1", "2026-08-19T10:00:00Z", "https://a.test/1")));
        when(factory.find(any(), eq("beta")))
                .thenReturn(new FakeFeedSource("beta")
                        .failingWith(new FeedException("upstream 503")));

        CentauriPage result = service.fetchPage(request(ALPHA, BETA), SCOPE);

        assertThat(result.items()).hasSize(1);
        assertThat(result.notes()).singleElement()
                .satisfies(n -> {
                    assertThat(n.kind()).isEqualTo(CentauriNote.Kind.FAILED);
                    assertThat(n.detail()).contains("503");
                });
        // Reported so a cooldown can be set and the next page skips it early.
        verify(agrajagChecker, times(1)).handle(
                eq(CentauriSettings.cooldownSubject("beta")), any(), any());
    }

    @Test
    void fetchPage_exhaustedStream_isNotAskedAgain() {
        FakeFeedSource alpha = new FakeFeedSource("alpha")
                .serving(page(item("a1", "2026-08-19T10:00:00Z", "https://a.test/1")));
        when(factory.find(any(), eq("alpha"))).thenReturn(alpha);
        CentauriCursorCodec codec = new CentauriCursorCodec(JsonMapper.builder().build());
        String cursor = codec.encode(new CentauriCursor(
                java.util.Map.of(), null, java.util.Set.of(ALPHA.key())));

        CentauriPage result = service.fetchPage(
                new CentauriPageRequest(List.of(ALPHA), FeedFilter.none(), 20,
                        FeedDirection.OLDER, cursor),
                SCOPE);

        assertThat(alpha.received()).isEmpty();
        assertThat(result.items()).isEmpty();
    }

    @Test
    void fetchPage_appliesOverFetchWhenTheFilterCannotBePushedDown() {
        source("alpha", page(item("a1", "2026-08-19T10:00:00Z", "https://a.test/1")));
        FeedFilter keywords = new FeedFilter(
                null, java.util.Set.of(), List.of(), List.of("advert"), null);

        service.fetchPage(new CentauriPageRequest(
                List.of(ALPHA), keywords, 10, FeedDirection.OLDER, null), SCOPE);

        FakeFeedSource alpha = (FakeFeedSource) factory.find(SCOPE, "alpha");
        assertThat(alpha.received()).singleElement()
                .satisfies(f -> {
                    assertThat(f.limit()).isEqualTo(30);
                    // The keyword list never travels — the source has no surface
                    // for it, and post-filtering catches it anyway.
                    assertThat(f.pushdown().exclude()).isEmpty();
                });
    }

    @Test
    void fetchPage_withoutStreams_isAnEmptyPageNotAnError() {
        CentauriPage result = service.fetchPage(CentauriPageRequest.of(List.of()), SCOPE);

        assertThat(result.items()).isEmpty();
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void fetchPage_withoutProject_isRefused() {
        assertThatThrownBy(() -> service.fetchPage(
                CentauriPageRequest.of(List.of(ALPHA)), new FeedScope("acme", "", null, null)))
                .isInstanceOf(CentauriException.class)
                .hasMessageContaining("project scope");
    }

    @Test
    void fetchPage_sourceWithoutTheSelectedFacet_isANoteAndIsNotAsked() {
        source("alpha", page(item("a1", "2026-08-19T10:00:00Z", "https://a.test/1")));
        when(factory.find(any(), eq("beta"))).thenReturn(
                new FakeFeedSource("beta")
                        .declaringFacet("origin-place")
                        .serving(page(item("b1", "2026-08-19T09:00:00Z", "https://b.test/1"))));

        CentauriPage result = service.fetchPage(new CentauriPageRequest(
                List.of(ALPHA, BETA),
                new FeedFilter(null, Set.of(), List.of(), List.of(), null,
                        Map.of("origin-place", List.of("m49:142"))),
                10, FeedDirection.OLDER, null), SCOPE);

        // Beta answers the question and contributes; alpha never declared the
        // dimension, so it is left out rather than let through unfiltered.
        assertThat(result.items()).extracting(i -> i.item().id()).containsExactly("b1");
        assertThat(result.notes()).singleElement()
                .satisfies(n -> {
                    assertThat(n.sourceId()).isEqualTo("alpha");
                    assertThat(n.kind()).isEqualTo(CentauriNote.Kind.MISSING_FACET);
                    assertThat(n.detail()).isEqualTo("origin-place");
                });
        FakeFeedSource alpha = (FakeFeedSource) factory.find(SCOPE, "alpha");
        assertThat(alpha.received()).isEmpty();
    }

    @Test
    void fetchPage_declaredFacet_travelsToTheSource() {
        when(factory.find(any(), eq("alpha"))).thenReturn(
                new FakeFeedSource("alpha")
                        .declaringFacet("origin-place")
                        .serving(page(item("a1", "2026-08-19T10:00:00Z", "https://a.test/1"))));

        service.fetchPage(new CentauriPageRequest(
                List.of(ALPHA),
                new FeedFilter(null, Set.of(), List.of(), List.of(), null,
                        Map.of("origin-place", List.of("m49:142"))),
                10, FeedDirection.OLDER, null), SCOPE);

        FakeFeedSource alpha = (FakeFeedSource) factory.find(SCOPE, "alpha");
        assertThat(alpha.received()).singleElement()
                .satisfies(f -> assertThat(f.pushdown().facets())
                        .containsEntry("origin-place", List.of("m49:142")));
    }

    // ── helpers ──────────────────────────────────────────────────────

    private void source(String id, FeedPage... pages) {
        when(factory.find(any(), eq(id))).thenReturn(new FakeFeedSource(id).serving(pages));
    }

    private static FeedPage page(de.mhus.vance.toolpack.feed.FeedItem... items) {
        return new FeedPage(List.of(items), null, false);
    }

    private static CentauriPageRequest request(FeedStream... streams) {
        return CentauriPageRequest.of(List.of(streams));
    }
}
