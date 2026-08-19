package de.mhus.vance.addon.brain.centauri.protocols;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.toolpack.feed.FeedDirection;
import de.mhus.vance.toolpack.feed.FeedException;
import de.mhus.vance.toolpack.feed.FeedFetch;
import de.mhus.vance.toolpack.feed.FeedFilter;
import de.mhus.vance.toolpack.feed.FeedInstanceConfig;
import de.mhus.vance.toolpack.feed.FeedItem;
import de.mhus.vance.toolpack.feed.FeedPage;
import de.mhus.vance.toolpack.feed.FeedSourceInstance;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * USGS is the source paged by <i>time</i> rather than by id, so the cases here
 * are mostly about the cursor: what bound it becomes, and how the inclusive
 * bound is stopped from returning the same entry twice.
 */
class UsgsFeedInstanceTest {

    private static final String TWO_QUAKES = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","id":"nn00923502",
               "properties":{"mag":1.53,"place":"80 km NE of Tonopah, Nevada",
                 "time":1787129375789,"url":"https://earthquake.usgs.gov/earthquakes/eventpage/nn00923502",
                 "type":"earthquake","title":"M 1.5 - 80 km NE of Tonopah, Nevada"},
               "geometry":{"type":"Point","coordinates":[-116.4746,38.4792,0.0029]}},
              {"type":"Feature","id":"nc75421112",
               "properties":{"mag":0.46,"place":"2 km E of The Geysers, CA",
                 "time":1787128995740,"url":"https://earthquake.usgs.gov/earthquakes/eventpage/nc75421112",
                 "type":"earthquake","title":"M 0.5 - 2 km E of The Geysers, CA"},
               "geometry":{"type":"Point","coordinates":[-122.7,38.8,1.5]}}]}""";

    private final RecordingHttpClient http = new RecordingHttpClient();

    @Test
    void capabilities_needNoRoundTrip() {
        instance().capabilities();

        // What the service can do is a property of the service, not of its state.
        assertThat(http.callCount()).isZero();
    }

    @Test
    void fetch_mapsFeaturesIncludingCoordinates() {
        http.replyAny(200, TWO_QUAKES);

        FeedPage page = instance().fetch(fetch(20, null, FeedDirection.OLDER));

        assertThat(page.items()).hasSize(2);
        FeedItem first = page.items().get(0);
        assertThat(first.id()).isEqualTo("nn00923502");
        assertThat(first.publishedAt()).isEqualTo(Instant.ofEpochMilli(1787129375789L));
        assertThat(first.title()).isEqualTo("M 1.5 - 80 km NE of Tonopah, Nevada");
        assertThat(first.summary()).contains("M 1.53").contains("Tonopah").contains("depth");
        assertThat(first.tags()).containsExactly("earthquake");
        assertThat(first.extras()).containsEntry("latitude", 38.4792)
                .containsEntry("longitude", -116.4746)
                .containsKey("depthKm");
    }

    @Test
    void fetch_entriesCarryNoLanguage() {
        http.replyAny(200, TWO_QUAKES);

        FeedPage page = instance().fetch(fetch(20, null, FeedDirection.OLDER));

        // Deliberate: this is the source that keeps the "an undeclared language
        // passes a language filter" rule honest. With the opposite rule the
        // stream would be permanently empty for anyone who set one.
        assertThat(page.items()).allSatisfy(i -> assertThat(i.language()).isNull());
    }

    @Test
    void fetch_olderDirection_usesTheCursorAsEndTime() {
        http.replyAny(200, TWO_QUAKES);
        String cursor = "2026-08-19T06:00:00Z|us6000tltq";

        instance().fetch(fetch(20, cursor, FeedDirection.OLDER));

        assertThat(http.last().query())
                .contains("orderby=time")
                .contains("endtime=2026-08-19T06:00:00Z");
    }

    @Test
    void fetch_newerDirection_flipsOrderAndBound() {
        http.replyAny(200, TWO_QUAKES);

        instance().fetch(fetch(20, "2026-08-19T06:00:00Z|x", FeedDirection.NEWER));

        assertThat(http.last().query())
                .contains("orderby=time-asc")
                .contains("starttime=2026-08-19T06:00:00Z")
                .doesNotContain("endtime=");
    }

    @Test
    void fetch_anchorFromTheCursor_isNotDeliveredTwice() {
        http.replyAny(200, TWO_QUAKES);
        // The service's endtime is inclusive, so the anchor comes back with the
        // next page — and a repeat across pages would be a visible duplicate,
        // because de-duplication is page-local.
        String cursor = "2026-08-19T10:09:35.789Z|nn00923502";

        FeedPage page = instance().fetch(fetch(20, cursor, FeedDirection.OLDER));

        assertThat(page.items()).extracting(FeedItem::id).containsExactly("nc75421112");
    }

    @Test
    void fetch_selectorBecomesAMagnitudeFloor() {
        http.replyAny(200, TWO_QUAKES);

        instance().fetch(new FeedFetch(
                "m4.5", null, FeedDirection.OLDER, 20, FeedFilter.none(), null));

        assertThat(http.last().query()).contains("minmagnitude=4.5");
    }

    @Test
    void fetch_allSelector_setsNoFloor() {
        http.replyAny(200, TWO_QUAKES);

        instance().fetch(fetch(20, null, FeedDirection.OLDER));

        assertThat(http.last().query()).doesNotContain("minmagnitude");
    }

    @Test
    void fetch_unknownSelector_isRefusedWithTheAllowedValues() {
        http.replyAny(200, TWO_QUAKES);

        assertThatThrownBy(() -> instance().fetch(new FeedFetch(
                "volcanoes", null, FeedDirection.OLDER, 20, FeedFilter.none(), null)))
                .isInstanceOf(FeedException.class)
                .hasMessageContaining("m4.5");
    }

    @Test
    void fetch_sincePushdown_becomesStartTime() {
        http.replyAny(200, TWO_QUAKES);

        instance().fetch(new FeedFetch(
                "all", null, FeedDirection.OLDER, 20,
                new FeedFilter(null, Set.of(), List.of(), List.of(),
                        Instant.parse("2026-08-01T00:00:00Z")),
                null));

        assertThat(http.last().query()).contains("starttime=2026-08-01T00:00:00Z");
    }

    @Test
    void fetch_fullPage_reportsMoreToCome() {
        http.replyAny(200, TWO_QUAKES);

        // The service does not say. A full page is the only available signal, and
        // a wrong "more" costs one empty request because the cursor is a time.
        assertThat(instance().fetch(fetch(2, null, FeedDirection.OLDER)).hasMore()).isTrue();
        assertThat(instance().fetch(fetch(20, null, FeedDirection.OLDER)).hasMore()).isFalse();
    }

    @Test
    void cursorAfter_carriesTimeAndId() {
        FeedItem item = FakeFeedItems.at("abc", "2026-08-19T10:00:00Z");

        assertThat(instance().cursorAfter(item)).isEqualTo("2026-08-19T10:00:00Z|abc");
    }

    @Test
    void fetch_httpError_carriesTheStatus() {
        http.replyAny(503, "maintenance");

        assertThatThrownBy(() -> instance().fetch(fetch(20, null, FeedDirection.OLDER)))
                .isInstanceOf(FeedException.class)
                .hasMessageContaining("HTTP 503");
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static FeedFetch fetch(int limit, String cursor, FeedDirection direction) {
        return new FeedFetch("all", cursor, direction, limit, FeedFilter.none(), null);
    }

    private FeedSourceInstance instance() {
        FeedInstanceConfig cfg = new FeedInstanceConfig(
                "usgs", UsgsFeedProtocol.ID, UsgsFeedProtocol.DEFAULT_BASE_URL,
                "", () -> null, Map.of());
        return new UsgsFeedProtocol(http, JsonMapper.builder().build()).instantiate(cfg);
    }
}
