package de.mhus.vance.brain.centauri.protocols;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.toolpack.feed.FeedActor;
import de.mhus.vance.toolpack.feed.FeedCapabilities;
import de.mhus.vance.toolpack.feed.FeedDirection;
import de.mhus.vance.toolpack.feed.FeedException;
import de.mhus.vance.toolpack.feed.FeedFetch;
import de.mhus.vance.toolpack.feed.FeedFilter;
import de.mhus.vance.toolpack.feed.FeedInstanceConfig;
import de.mhus.vance.toolpack.feed.FeedItem;
import de.mhus.vance.toolpack.feed.FeedPage;
import de.mhus.vance.toolpack.feed.FeedReportReason;
import de.mhus.vance.toolpack.feed.FeedSelectorMode;
import de.mhus.vance.toolpack.feed.FeedSignal;
import de.mhus.vance.toolpack.feed.FeedSignalOutcome;
import de.mhus.vance.toolpack.feed.FeedSignalRequest;
import de.mhus.vance.toolpack.feed.FeedSourceInstance;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The protocol is where our side meets the ode contract, so these tests check
 * both directions of the translation: what goes onto the wire, and what a
 * foreign answer becomes.
 */
class OdeFeedInstanceTest {

    private static final String BASE = "https://hrafnagud.example";

    private final RecordingHttpClient http = new RecordingHttpClient();

    // ── capabilities ─────────────────────────────────────────────────

    @Test
    void capabilities_areMappedIncludingTtlAndSignals() {
        http.reply("/capabilities", 200, """
                {"selectorMode":"ENUMERABLE","selectorKinds":["CATEGORY"],
                 "pushdownTextSearch":true,"pushdownLanguage":false,"pushdownSince":true,
                 "supportsNewerDirection":true,"carriesFullBody":false,"maxPageSize":100,
                 "signalsAccepted":["REPORT","REQUEST"],"carriesControlUrl":true,
                 "capabilitiesTtl":"PT30M"}""");

        FeedCapabilities caps = instance().capabilities();

        assertThat(caps.selectorMode()).isEqualTo(FeedSelectorMode.ENUMERABLE);
        assertThat(caps.pushdownTextSearch()).isTrue();
        assertThat(caps.pushdownLanguage()).isFalse();
        assertThat(caps.maxPageSize()).isEqualTo(100);
        assertThat(caps.signalsAccepted())
                .containsExactlyInAnyOrder(FeedSignal.REPORT, FeedSignal.REQUEST);
        assertThat(caps.capabilitiesTtl()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void capabilities_unknownEnumValue_isDroppedNotFatal() {
        http.reply("/capabilities", 200, """
                {"selectorMode":"ENUMERABLE","selectorKinds":["CATEGORY","TELEPATHY"],
                 "signalsAccepted":["REPORT","APPLAUD"],"maxPageSize":10}""");

        FeedCapabilities caps = instance().capabilities();

        // A newer source may name a kind this build does not have; refusing the
        // whole response would make every capability addition breaking.
        assertThat(caps.signalsAccepted()).containsExactly(FeedSignal.REPORT);
        assertThat(caps.selectorKinds()).hasSize(1);
    }

    @Test
    void capabilities_carryNoReaderHeader() {
        http.reply("/capabilities", 200, "{\"selectorMode\":\"NONE\"}");

        instance().capabilities();

        // They describe the source, not the person asking, which is what makes
        // them cacheable across all readers.
        assertThat(http.last().headers()).doesNotContainKey(OdeFeedInstance.HEADER_READER);
    }

    // ── selectors ────────────────────────────────────────────────────

    @Test
    void selectors_areMappedWithLabelFallback() {
        http.reply("/selectors", 200, """
                [{"value":"world","label":"World","kind":"CATEGORY","language":"en"},
                 {"value":"tech","kind":"CATEGORY"}]""");

        var selectors = instance().listSelectors();

        assertThat(selectors).hasSize(2);
        assertThat(selectors.get(0).language()).isEqualTo("en");
        assertThat(selectors.get(1).label()).isEqualTo("tech");
    }

    // ── items ────────────────────────────────────────────────────────

    @Test
    void fetch_buildsTheQueryFromTheRequest() {
        http.reply("/items", 200, """
                {"items":[],"hasMore":false}""");

        instance().fetch(new FeedFetch(
                "world", "cur-7", FeedDirection.OLDER, 30,
                new FeedFilter("berlin", Set.of("de"), List.of(), List.of(),
                        Instant.parse("2026-08-01T00:00:00Z")),
                null));

        String url = http.last().url().toString();
        assertThat(url).contains("selector=world")
                .contains("cursor=cur-7")
                .contains("direction=OLDER")
                .contains("limit=30")
                .contains("text=berlin")
                .contains("languages=de")
                .contains("since=2026-08-01T00%3A00%3A00Z");
    }

    @Test
    void fetch_mapsItemsAndPaging() {
        http.reply("/items", 200, """
                {"items":[{"id":"i1","publishedAt":"2026-08-19T10:00:00Z","title":"First",
                           "url":"https://news.example/1","summary":"teaser","language":"de",
                           "tags":["politics"]}],
                 "nextCursor":"cur-8","hasMore":true}""");

        FeedPage page = instance().fetch(fetch());

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).id()).isEqualTo("i1");
        assertThat(page.items().get(0).publishedAt())
                .isEqualTo(Instant.parse("2026-08-19T10:00:00Z"));
        assertThat(page.items().get(0).tags()).containsExactly("politics");
        assertThat(page.nextCursor()).isEqualTo("cur-8");
        assertThat(page.hasMore()).isTrue();
    }

    @Test
    void fetch_entryWithoutRequiredFields_isSkippedNotFatal() {
        http.reply("/items", 200, """
                {"items":[{"id":"good","publishedAt":"2026-08-19T10:00:00Z",
                           "url":"https://news.example/1"},
                          {"id":"no-time","url":"https://news.example/2"},
                          {"publishedAt":"2026-08-19T09:00:00Z","url":"https://news.example/3"}],
                 "hasMore":false}""");

        FeedPage page = instance().fetch(fetch());

        // One malformed row must not cost the reader the others.
        assertThat(page.items()).extracting(i -> i.id()).containsExactly("good");
    }

    @Test
    void fetch_sendsCredentialAndReaderPseudonym() {
        http.reply("/items", 200, "{\"items\":[],\"hasMore\":false}");

        instanceWithCredential("s3cret").fetch(new FeedFetch(
                "world", null, FeedDirection.OLDER, 20, FeedFilter.none(),
                new FeedActor("pseudo-42")));

        assertThat(http.last().headers())
                .containsEntry("Authorization", "Bearer s3cret")
                .containsEntry(OdeFeedInstance.HEADER_READER, "pseudo-42");
    }

    @Test
    void fetch_anonymously_sendsNoReaderHeader() {
        http.reply("/items", 200, "{\"items\":[],\"hasMore\":false}");

        instance().fetch(fetch());

        assertThat(http.last().headers()).doesNotContainKey(OdeFeedInstance.HEADER_READER);
    }

    @Test
    void fetch_honoursACustomFeedPath() {
        http.reply("/feeds/v2/items", 200, "{\"items\":[],\"hasMore\":false}");

        instanceWith(Map.of(OdeFeedProtocol.EXTRA_FEED_PATH, "/feeds/v2"), null).fetch(fetch());

        assertThat(http.last().url().toString()).startsWith(BASE + "/feeds/v2/items");
    }

    @Test
    void fetch_httpError_carriesTheStatusForClassification() {
        http.reply("/items", 503, "upstream down");

        assertThatThrownBy(() -> instance().fetch(fetch()))
                .isInstanceOf(FeedException.class)
                .hasMessageContaining("HTTP 503");
    }

    // ── controlUrl hardening ─────────────────────────────────────────

    @Test
    void fetch_controlUrlFromAnotherHost_isDropped() {
        http.reply("/items", 200, """
                {"items":[{"id":"i1","publishedAt":"2026-08-19T10:00:00Z",
                           "url":"https://news.example/1",
                           "controlUrl":"https://evil.example/take-over"}],
                 "hasMore":false}""");

        FeedPage page = instance().fetch(fetch());

        // Without the host check a compromised source could deep-link anywhere.
        assertThat(page.items().get(0).controlUrl()).isNull();
    }

    @Test
    void fetch_controlUrlOnTheSourceHost_isKept() {
        http.reply("/items", 200, """
                {"items":[{"id":"i1","publishedAt":"2026-08-19T10:00:00Z",
                           "url":"https://news.example/1",
                           "controlUrl":"https://hrafnagud.example/admin/item/i1"}],
                 "hasMore":false}""");

        FeedPage page = instance().fetch(fetch());

        assertThat(page.items().get(0).controlUrl())
                .isEqualTo("https://hrafnagud.example/admin/item/i1");
    }

    @Test
    void fetch_plainHttpControlUrl_isDropped() {
        http.reply("/items", 200, """
                {"items":[{"id":"i1","publishedAt":"2026-08-19T10:00:00Z",
                           "url":"https://news.example/1",
                           "controlUrl":"http://hrafnagud.example/admin"}],
                 "hasMore":false}""");

        assertThat(instance().fetch(fetch()).items().get(0).controlUrl()).isNull();
    }

    // ── one entry in full ────────────────────────────────────────────

    @Test
    void loadItem_parsesTheWholeEntry_notJustItsText() {
        http.reply("/item/i1", 200, """
                {"id":"i1","publishedAt":"2026-08-19T10:00:00Z","title":"Headline",
                 "url":"https://x.test/1","summary":"teaser","body":"the whole article",
                 "language":"en","tags":["a"],"extras":{"originPlace":"Germany"}}""");

        FeedItem item = instance().loadItem("i1", null).orElseThrow();

        // The same record a page carries — the detail is not a second shape.
        assertThat(item.body()).isEqualTo("the whole article");
        assertThat(item.title()).isEqualTo("Headline");
        assertThat(item.language()).isEqualTo("en");
        assertThat(item.extras()).containsEntry("originPlace", "Germany");
    }

    @Test
    void loadItem_unknownEntry_isEmptyNotAFailure() {
        http.reply("/item/gone", 404, "");

        // An entry may have aged out between the page and the click.
        assertThat(instance().loadItem("gone", null)).isEmpty();
    }

    // ── signal ───────────────────────────────────────────────────────

    @Test
    void sendSignal_mapsTheContractStatuses() {
        assertThat(signalWithStatus(202)).isEqualTo(FeedSignalOutcome.ACCEPTED);
        assertThat(signalWithStatus(501)).isEqualTo(FeedSignalOutcome.UNSUPPORTED);
        assertThat(signalWithStatus(409)).isEqualTo(FeedSignalOutcome.REJECTED);
    }

    @Test
    void sendSignal_unexpectedStatus_isAFailure() {
        http.reply("/signal", 500, "boom");

        assertThatThrownBy(() -> instance().sendSignal(report()))
                .isInstanceOf(FeedException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void sendSignal_postsTheClosedVocabulary() {
        http.reply("/signal", 202, "{\"outcome\":\"ACCEPTED\"}");

        instance().sendSignal(new FeedSignalRequest(
                "i1", FeedSignal.REPORT, FeedReportReason.WRONG_CATEGORY, null,
                "filed under sport", new FeedActor("pseudo-42")));

        assertThat(http.last().method()).isEqualTo("POST");
        assertThat(http.last().body())
                .contains("\"itemId\":\"i1\"")
                .contains("\"signal\":\"REPORT\"")
                .contains("\"reason\":\"WRONG_CATEGORY\"")
                .contains("\"note\":\"filed under sport\"");
        assertThat(http.last().headers())
                .containsEntry(OdeFeedInstance.HEADER_READER, "pseudo-42");
    }

    // ── helpers ──────────────────────────────────────────────────────

    private FeedSignalOutcome signalWithStatus(int status) {
        RecordingHttpClient fresh = new RecordingHttpClient();
        fresh.reply("/signal", status, "{}");
        return build(fresh, Map.of(), null).sendSignal(report());
    }

    private static FeedSignalRequest report() {
        return FeedSignalRequest.report("i1", FeedReportReason.SPAM, null, null);
    }

    private static FeedFetch fetch() {
        return new FeedFetch("world", null, FeedDirection.OLDER, 20, FeedFilter.none(), null);
    }

    private FeedSourceInstance instance() {
        return build(http, Map.of(), null);
    }

    private FeedSourceInstance instanceWithCredential(String credential) {
        return build(http, Map.of(), credential);
    }

    private FeedSourceInstance instanceWith(
            Map<String, Object> extras, @Nullable String credential) {
        return build(http, extras, credential);
    }

    private static FeedSourceInstance build(
            CentauriHttpClient http, Map<String, Object> extras, @Nullable String credential) {
        FeedInstanceConfig cfg = new FeedInstanceConfig(
                "hrafnagud-main", OdeFeedProtocol.ID, BASE,
                "centauri.endpoint.hrafnagud-main.apiKey",
                () -> credential,
                extras);
        return new OdeFeedProtocol(http, JsonMapper.builder().build()).instantiate(cfg);
    }

}
