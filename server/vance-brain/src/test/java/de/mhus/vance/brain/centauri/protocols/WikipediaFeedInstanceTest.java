package de.mhus.vance.brain.centauri.protocols;

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
 * Wikipedia contributes what USGS cannot: an opaque upstream continue token, a
 * real {@code controlUrl}, and entries that carry a language.
 */
class WikipediaFeedInstanceTest {

    private static final String BASE = "https://de.wikipedia.org";

    private static final String TWO_CHANGES = """
            {"batchcomplete":true,
             "continue":{"rccontinue":"20260819090709|383610853","continue":"-||"},
             "query":{"recentchanges":[
               {"type":"new","ns":0,"title":"Aminosäuremuster","pageid":13955062,
                "revid":269795272,"old_revid":0,"rcid":383610864,"user":"Calle Cool",
                "bot":false,"new":true,"minor":false,"oldlen":0,"newlen":30,
                "timestamp":"2026-08-19T09:07:23Z","comment":"Weiterleitung erstellt"},
               {"type":"edit","ns":0,"title":"Reliant Scimitar SS","pageid":5010443,
                "revid":269795270,"old_revid":269066542,"rcid":383610862,
                "user":"Matthias v.d. Elbe","bot":true,"new":false,"minor":true,
                "oldlen":4860,"newlen":4863,"timestamp":"2026-08-19T09:07:21Z",
                "comment":"Typo"}]}}""";

    private final RecordingHttpClient http = new RecordingHttpClient();

    @Test
    void fetch_buildsTheActionApiQuery() {
        http.replyAny(200, TWO_CHANGES);

        instance().fetch(new FeedFetch(
                "article", null, FeedDirection.OLDER, 50, FeedFilter.none(), null));

        String query = http.last().query();
        assertThat(query).contains("action=query")
                .contains("list=recentchanges")
                .contains("formatversion=2")
                .contains("rclimit=50")
                .contains("rcdir=older")
                .contains("rcnamespace=0");
    }

    @Test
    void fetch_sendsADescriptiveUserAgent() {
        http.replyAny(200, TWO_CHANGES);

        instance().fetch(fetch(null, FeedDirection.OLDER));

        // Wikimedia blocks generic agents outright.
        assertThat(http.last().headers())
                .containsEntry("User-Agent", WikipediaFeedInstance.USER_AGENT);
    }

    @Test
    void fetch_mapsChangesWithLanguageAuthorAndTags() {
        http.replyAny(200, TWO_CHANGES);

        FeedPage page = instance().fetch(fetch(null, FeedDirection.OLDER));

        assertThat(page.items()).hasSize(2);
        FeedItem first = page.items().get(0);
        assertThat(first.id()).isEqualTo("383610864");
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-08-19T09:07:23Z"));
        assertThat(first.title()).isEqualTo("Aminosäuremuster");
        assertThat(first.author()).isEqualTo("Calle Cool");
        assertThat(first.language()).isEqualTo("de");
        assertThat(first.tags()).containsExactly("new");
        assertThat(first.summary()).startsWith("(+30)").contains("Weiterleitung");
        assertThat(first.extras()).containsEntry("sizeDelta", 30L);

        assertThat(page.items().get(1).tags()).containsExactly("edit", "bot", "minor");
    }

    @Test
    void fetch_articleUrlIsThePage_controlUrlIsTheDiff() {
        http.replyAny(200, TWO_CHANGES);

        FeedItem second = instance().fetch(fetch(null, FeedDirection.OLDER)).items().get(1);

        assertThat(second.url()).isEqualTo(BASE + "/wiki/Reliant_Scimitar_SS");
        // The diff is the wiki's own view of exactly this change; url is the
        // article. Two different destinations, two different fields.
        assertThat(second.controlUrl())
                .isEqualTo(BASE + "/w/index.php?diff=269795270&oldid=269066542");
    }

    @Test
    void fetch_titleWithSpaces_becomesAnUnderscoreUrl() {
        http.replyAny(200, TWO_CHANGES);

        FeedItem first = instance().fetch(fetch(null, FeedDirection.OLDER)).items().get(0);

        assertThat(first.url()).isEqualTo(BASE + "/wiki/Aminos%C3%A4uremuster");
    }

    @Test
    void fetch_usesTheApiContinueTokenAsNextCursor() {
        http.replyAny(200, TWO_CHANGES);

        FeedPage page = instance().fetch(fetch(null, FeedDirection.OLDER));

        // The API's own token is exact and exclusive, so it beats anything derived.
        assertThat(page.nextCursor()).isEqualTo("20260819090709|383610853");
        assertThat(page.hasMore()).isTrue();
    }

    @Test
    void fetch_withoutContinueToken_isTheEndOfTheStream() {
        http.replyAny(200, """
                {"batchcomplete":true,"query":{"recentchanges":[]}}""");

        FeedPage page = instance().fetch(fetch(null, FeedDirection.OLDER));

        assertThat(page.nextCursor()).isNull();
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void fetch_cursorTravelsAsRcContinueAndItsAnchorIsDropped() {
        http.replyAny(200, TWO_CHANGES);
        String cursor = "20260819090723|383610864";

        FeedPage page = instance().fetch(fetch(cursor, FeedDirection.OLDER));

        assertThat(http.last().query()).contains("rccontinue=20260819090723|383610864");
        // rccontinue names the entry to resume at, so the anchor arrives again.
        assertThat(page.items()).extracting(FeedItem::id).containsExactly("383610862");
    }

    @Test
    void fetch_sincePushdown_landsOnTheCorrectSideDependingOnDirection() {
        http.replyAny(200, TWO_CHANGES);
        FeedFilter since = new FeedFilter(null, Set.of(), List.of(), List.of(),
                Instant.parse("2026-08-01T00:00:00Z"));

        instance().fetch(new FeedFetch(
                "all", null, FeedDirection.OLDER, 20, since, null));
        assertThat(http.last().query()).contains("rcend=2026-08-01T00:00:00Z");

        instance().fetch(new FeedFetch(
                "all", null, FeedDirection.NEWER, 20, since, null));
        assertThat(http.last().query()).contains("rcstart=2026-08-01T00:00:00Z");
    }

    @Test
    void cursorAfter_reproducesTheRcContinueFormat() {
        FeedItem item = FakeFeedItems.at("383610864", "2026-08-19T09:07:23Z");

        assertThat(instance().cursorAfter(item)).isEqualTo("20260819090723|383610864");
    }

    @Test
    void fetch_apiErrorWithStatus200_isStillAFailure() {
        // The Action API answers a rejected request with 200 and an error object,
        // so the status alone cannot tell success from failure.
        http.replyAny(200, """
                {"error":{"code":"unknown_rcnamespace","info":"Unrecognized value"}}""");

        assertThatThrownBy(() -> instance().fetch(fetch(null, FeedDirection.OLDER)))
                .isInstanceOf(FeedException.class)
                .hasMessageContaining("unknown_rcnamespace");
    }

    @Test
    void languageFromHost_takesTheFirstLabel() {
        assertThat(WikipediaFeedInstance.languageFromHost("https://de.wikipedia.org"))
                .isEqualTo("de");
        assertThat(WikipediaFeedInstance.languageFromHost("https://en.wikipedia.org"))
                .isEqualTo("en");
    }

    @Test
    void configuredLanguage_overridesTheHost() {
        http.replyAny(200, TWO_CHANGES);
        FeedInstanceConfig cfg = new FeedInstanceConfig(
                "wiki", WikipediaFeedProtocol.ID, "https://commons.wikimedia.org",
                "", () -> null, Map.of(WikipediaFeedProtocol.EXTRA_LANGUAGE, "mul"));
        FeedSourceInstance source =
                new WikipediaFeedProtocol(http, JsonMapper.builder().build()).instantiate(cfg);

        FeedPage page = source.fetch(fetch(null, FeedDirection.OLDER));

        assertThat(page.items().get(0).language()).isEqualTo("mul");
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static FeedFetch fetch(String cursor, FeedDirection direction) {
        return new FeedFetch("all", cursor, direction, 20, FeedFilter.none(), null);
    }

    private FeedSourceInstance instance() {
        FeedInstanceConfig cfg = new FeedInstanceConfig(
                "wikipedia-de", WikipediaFeedProtocol.ID, BASE, "", () -> null, Map.of());
        return new WikipediaFeedProtocol(http, JsonMapper.builder().build()).instantiate(cfg);
    }
}
