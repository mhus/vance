package de.mhus.vance.addon.brain.mastodon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.toolpack.feed.FeedCapabilities;
import de.mhus.vance.toolpack.feed.FeedDirection;
import de.mhus.vance.toolpack.feed.FeedException;
import de.mhus.vance.toolpack.feed.FeedFetch;
import de.mhus.vance.toolpack.feed.FeedFilter;
import de.mhus.vance.toolpack.feed.FeedInstanceConfig;
import de.mhus.vance.toolpack.feed.FeedItem;
import de.mhus.vance.toolpack.feed.FeedPage;
import de.mhus.vance.toolpack.feed.FeedSelectorKind;
import de.mhus.vance.toolpack.feed.FeedSelectorMode;
import de.mhus.vance.toolpack.feed.FeedSourceInstance;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real statuses from one page of {@code mstdn.social}, fetched on 2026-08-23
 * and trimmed to the fields that matter
 * ({@code planning/centauri-mastodon-messung.md} §6).
 *
 * <p>They are the fixture on purpose: that single page contained every awkward
 * case at once — a bridged entry 36 hours older than its position, a post with
 * no text at all, an entry without a language, nested hashtag anchors, and a
 * local post whose id time matches its {@code created_at} exactly. A
 * hand-written fixture would have had none of them.
 *
 * <p>The last entry is the one exception, added after the browser run: a
 * content-warning post with no {@code url}. The measured page happened to
 * contain neither, and both showed up within a minute of live scrolling.
 */
class MastodonFeedInstanceTest {

    private static final String BASE = "https://mstdn.social";

    /** Delivery order = id descending, which is how the API returns it. */
    private static final String PAGE = """
            [
              {"id":"117144208605002329","created_at":"2026-08-23T09:52:48.000Z",
               "language":"en","spoiler_text":"","sensitive":false,"reblog":null,
               "url":"https://mastodon.scot/@paka/117144208552689538",
               "uri":"https://mastodon.scot/users/paka/statuses/117144208552689538",
               "content":"<p>6 EU countries intensifying calls for bloc-wide <a href=\\"https://mastodon.scot/tags/WindfallTax\\" class=\\"mention hashtag\\">#<span>WindfallTax</span></a> on <a href=\\"https://mastodon.scot/tags/oil\\" class=\\"mention hashtag\\">#<span>oil</span></a> companies as profits surge</p>",
               "account":{"acct":"paka@mastodon.scot","display_name":"paka","bot":false},
               "media_attachments":[],
               "tags":[{"name":"windfalltax"},{"name":"oil"}],
               "replies_count":1,"reblogs_count":4,"favourites_count":7,"edited_at":null},

              {"id":"117144208580248837","created_at":"2026-08-21T21:53:33.000Z",
               "language":"en","spoiler_text":"","sensitive":false,"reblog":null,
               "url":"https://flipboard.com/@androidauth/android-authority/a-8c3qdxEcRTmkxk",
               "uri":"https://flipboard.com/@androidauth/android-authority/a-8c3qdxEcRTmkxk",
               "content":"<p>The Insignia 32-inch Fire TV falls to just $69.99 — a great price for a second screen<br><a href=\\"https://www.androidauthority.com/deal?utm_source=flipboard&amp;utm_medium=activitypub\\">androidauthority.com</a></p>",
               "account":{"acct":"Androidauth@flipboard.com","display_name":"Android Authority","bot":false},
               "media_attachments":[],
               "tags":[],
               "replies_count":0,"reblogs_count":0,"favourites_count":0,"edited_at":null},

              {"id":"117144208450561132","created_at":"2026-08-23T09:52:46.640Z",
               "language":"en","spoiler_text":"","sensitive":false,"reblog":null,
               "url":"https://mstdn.social/@tippfm/117144208450561132",
               "uri":"https://mstdn.social/users/tippfm/statuses/117144208450561132",
               "content":"<p>Local campaigner says failure to fund Skyclarys will impede benefits of future treatments</p>",
               "account":{"acct":"tippfm","display_name":"Tipp FM","bot":false},
               "media_attachments":[{"type":"image","preview_url":"https://files.mstdn.social/small/abc.jpg","url":"https://files.mstdn.social/original/abc.jpg","description":null}],
               "tags":[],
               "replies_count":0,"reblogs_count":0,"favourites_count":2,"edited_at":"2026-08-23T09:53:10.000Z"},

              {"id":"117144207483340822","created_at":"2026-08-23T09:52:29.000Z",
               "language":"en","spoiler_text":"","sensitive":true,"reblog":null,
               "url":"https://woof.group/@LeatherBoyDavid/117144207348198854",
               "uri":"https://woof.group/users/LeatherBoyDavid/statuses/117144207348198854",
               "content":"",
               "account":{"acct":"LeatherBoyDavid@woof.group","display_name":"David","bot":false},
               "media_attachments":[{"type":"image","preview_url":"https://files.mstdn.social/small/def.jpg","url":"https://files.mstdn.social/original/def.jpg","description":null}],
               "tags":[],
               "replies_count":0,"reblogs_count":0,"favourites_count":0,"edited_at":null},

              {"id":"117144207282246796","created_at":"2026-08-23T09:52:28.000Z",
               "language":null,"spoiler_text":"","sensitive":false,"reblog":null,
               "url":"https://rss-parrot.net/u/www.rainews.it/status/1787457742478406618",
               "uri":"https://rss-parrot.net/u/www.rainews.it/status/1787457742478406618",
               "content":"<p><strong>Attacco con spada alla scuola di Fagersta</strong></p><p>La vittima è una ragazza di 17 anni</p>",
               "account":{"acct":"www.rainews.it@rss-parrot.net","display_name":"Rai News","bot":true},
               "media_attachments":[],
               "tags":[],
               "replies_count":0,"reblogs_count":0,"favourites_count":0,"edited_at":null},

              {"id":"117144207100000000","created_at":"2026-08-23T09:52:26.000Z",
               "language":"en","spoiler_text":"  NSFW 18+ Nudity  ","sensitive":true,"reblog":null,
               "url":null,
               "uri":"https://xscape.zclan.cc/objects/c93fa57e-69c7-4809-8ac5-dbd9b0a34fd5",
               "content":"<p>text behind the warning</p>",
               "account":{"acct":"porncollector@xscape.zclan.cc","display_name":"c","bot":false},
               "media_attachments":[],
               "tags":[],
               "replies_count":0,"reblogs_count":0,"favourites_count":0,"edited_at":null}
            ]""";

    private static final String CLOSED_TIMELINE =
            "{\"error\":\"This method requires an authenticated user\"}";

    private final RecordingHttpClient http = new RecordingHttpClient();

    // ── capabilities ─────────────────────────────────────────────────

    @Test
    void capabilities_areFreeformWithNothingPushedDown() {
        FeedCapabilities caps = instance().capabilities();

        assertThat(caps.selectorMode()).isEqualTo(FeedSelectorMode.FREEFORM);
        assertThat(caps.selectorKinds())
                .containsExactlyInAnyOrder(FeedSelectorKind.HASHTAG, FeedSelectorKind.PUBLIC);
        // Status search needs ElasticSearch plus a user token; the timelines
        // filter by neither text nor language, and since_id is an id.
        assertThat(caps.pushdownTextSearch()).isFalse();
        assertThat(caps.pushdownLanguage()).isFalse();
        assertThat(caps.pushdownSince()).isFalse();
        // min_id exists — the first source in the tree that can walk upwards.
        assertThat(caps.supportsNewerDirection()).isTrue();
        assertThat(caps.maxPageSize()).isEqualTo(40);
        assertThat(caps.signalsAccepted()).isEmpty();
        assertThat(caps.carriesControlUrl()).isFalse();
    }

    @Test
    void listSelectors_isEmptyBecauseHashtagsCannotBeEnumerated() {
        assertThat(instance().listSelectors()).isEmpty();
    }

    @Test
    void validateSelector_delegatesToTheGrammar() {
        assertThat(instance().validateSelector("hashtag:linux")).isEmpty();
        assertThat(instance().validateSelector("hashtag:#linux")).isPresent();
    }

    // ── request building ─────────────────────────────────────────────

    @Test
    void fetch_hashtagSelectorHitsTheTagTimeline() {
        http.replyAny(200, PAGE);

        instance().fetch(fetch("hashtag:opensource", null, FeedDirection.OLDER, 40));

        assertThat(http.last().url().getPath())
                .isEqualTo("/api/v1/timelines/tag/opensource");
        assertThat(http.last().query()).contains("limit=40");
    }

    @Test
    void fetch_publicSelectorsPickTheFirehoseVariant() {
        http.replyAny(200, PAGE);
        FeedSourceInstance instance = instance();

        instance.fetch(fetch("public:all", null, FeedDirection.OLDER, 40));
        assertThat(http.last().url().getPath()).isEqualTo("/api/v1/timelines/public");
        assertThat(http.last().query()).doesNotContain("local").doesNotContain("remote");

        instance.fetch(fetch("public:local", null, FeedDirection.OLDER, 40));
        assertThat(http.last().query()).contains("local=true");

        instance.fetch(fetch("public:remote", null, FeedDirection.OLDER, 40));
        assertThat(http.last().query()).contains("remote=true");
    }

    @Test
    void fetch_capsTheLimitAtWhatTheApiAccepts() {
        http.replyAny(200, PAGE);

        // Measured: limit=100 is silently capped at 40, so asking for more only
        // makes the merge believe it got a short page.
        instance().fetch(fetch("public:all", null, FeedDirection.OLDER, 500));

        assertThat(http.last().query()).contains("limit=40");
    }

    @Test
    void fetch_cursorBecomesMaxIdOrMinIdByDirection() {
        http.replyAny(200, PAGE);
        FeedSourceInstance instance = instance();

        instance.fetch(fetch("public:all", "117144206757972320", FeedDirection.OLDER, 40));
        assertThat(http.last().query()).contains("max_id=117144206757972320");

        instance.fetch(fetch("public:all", "117144206757972320", FeedDirection.NEWER, 40));
        assertThat(http.last().query()).contains("min_id=117144206757972320");
    }

    @Test
    void fetch_sendsTheTokenOnlyWhenOneIsConfigured() {
        http.replyAny(200, PAGE);

        instance().fetch(fetch("public:all", null, FeedDirection.OLDER, 40));
        assertThat(http.last().headers()).doesNotContainKey("Authorization")
                .containsEntry("User-Agent", MastodonFeedInstance.USER_AGENT);

        withToken("app-token-123").fetch(fetch("public:all", null, FeedDirection.OLDER, 40));
        assertThat(http.last().headers()).containsEntry("Authorization", "Bearer app-token-123");
    }

    @Test
    void fetch_encodesANonAsciiTag() {
        http.replyAny(200, PAGE);

        instance().fetch(fetch("hashtag:Grüße", null, FeedDirection.OLDER, 40));

        assertThat(http.last().url().toString()).contains("/tag/Gr%C3%BC%C3%9Fe");
    }

    // ── ordering, the reason §2a exists ──────────────────────────────

    @Test
    void fetch_publishedAtIsTheIngestTimeSoThePageNeverRises() {
        http.replyAny(200, PAGE);

        FeedPage page = instance().fetch(fetch("public:all", null, FeedDirection.OLDER, 40));

        List<Instant> published = page.items().stream().map(FeedItem::publishedAt).toList();
        assertThat(MastodonStreamTime.isDescending(published)).isTrue();
        // The bridged entry sits second by id and would sort last by
        // created_at — 36 hours out of place, which is what would break the
        // merge's stable ordering across pages.
        FeedItem bridged = page.items().get(1);
        assertThat(bridged.publishedAt()).isEqualTo(Instant.parse("2026-08-23T09:52:48.619Z"));
        assertThat(bridged.extras())
                .containsEntry(MastodonFeedInstance.EXTRA_AUTHORED_AT, "2026-08-21T21:53:33Z");
    }

    @Test
    void fetch_authoredAtIsCarriedOnlyWhenItDiffers() {
        http.replyAny(200, PAGE);

        List<FeedItem> items = instance().fetch(
                fetch("public:all", null, FeedDirection.OLDER, 40)).items();

        // Only the bridged entry: for everything else the two timestamps are the
        // same event, and a duplicate line on every card teaches the reader to
        // ignore the one row that means something.
        assertThat(items).filteredOn(
                        i -> i.extras().containsKey(MastodonFeedInstance.EXTRA_AUTHORED_AT))
                .extracting(FeedItem::id)
                .containsExactly("117144208580248837");
    }

    // ── mapping ──────────────────────────────────────────────────────

    @Test
    void fetch_mapsAnOrdinaryStatus() {
        http.replyAny(200, PAGE);

        FeedItem first = instance().fetch(
                fetch("public:all", null, FeedDirection.OLDER, 40)).items().get(0);

        assertThat(first.id()).isEqualTo("117144208605002329");
        assertThat(first.url()).isEqualTo("https://mastodon.scot/@paka/117144208552689538");
        assertThat(first.author()).isEqualTo("@paka@mastodon.scot");
        assertThat(first.language()).isEqualTo("en");
        assertThat(first.tags()).containsExactly("windfalltax", "oil");
        // Hashtags survive the nested <span> intact.
        assertThat(first.body()).isEqualTo(
                "6 EU countries intensifying calls for bloc-wide #WindfallTax on #oil "
                        + "companies as profits surge");
        assertThat(first.title()).startsWith("6 EU countries intensifying calls for bloc-wide");
        assertThat(first.extras())
                .containsEntry("replies", 1L)
                .containsEntry("reblogs", 4L)
                .containsEntry("favourites", 7L);
        // No cursor token: the id IS the anchor for these timelines.
        assertThat(first.cursor()).isNull();
        assertThat(first.controlUrl()).isNull();
    }

    @Test
    void fetch_contextCarriesTheDisplayNameWhenItDiffersFromTheHandle() {
        http.replyAny(200, PAGE);

        List<FeedItem> items = instance().fetch(
                fetch("public:all", null, FeedDirection.OLDER, 40)).items();

        // The handle is already the `author` line; the `context` slot carries
        // the human half, which is the one thing a glance at the header wants
        // and `extraRows` would only show on a marked card.
        assertThat(items.get(0).extras())
                .containsEntry(MastodonFeedInstance.EXTRA_CONTEXT, "paka");
        // "Android Authority" is the display name; "Androidauth@flipboard.com"
        // is the handle, and the two being different is exactly when the slot
        // earns its row.
        assertThat(items.get(1).extras())
                .containsEntry(MastodonFeedInstance.EXTRA_CONTEXT, "Android Authority");
    }

    @Test
    void fetch_contextIsOmittedWhenTheDisplayNameOnlyEchoesTheHandle() {
        // A status whose author never set a display name: Mastodon echoes the
        // raw acct there, and putting it next to the `author` handle would read
        // as the same name twice in one row. No real fixture status happens to
        // be one, so this is a constructed echo.
        String page = """
                [{"id":"117144208605002329","created_at":"2026-08-23T09:52:48.000Z",
                 "language":"en","spoiler_text":"","sensitive":false,"reblog":null,
                 "url":"https://mstdn.social/@jane/117144208605002329",
                 "uri":"https://mstdn.social/users/jane/statuses/117144208605002329",
                 "content":"<p>a post</p>",
                 "account":{"acct":"jane","display_name":"jane","bot":false},
                 "media_attachments":[],"tags":[],
                 "replies_count":0,"reblogs_count":0,"favourites_count":0,"edited_at":null}]
                """;
        http.replyAny(200, page);

        FeedItem item = instance().fetch(
                fetch("public:all", null, FeedDirection.OLDER, 40)).items().get(0);

        assertThat(item.author()).isEqualTo("@jane");
        assertThat(item.extras()).doesNotContainKey(MastodonFeedInstance.EXTRA_CONTEXT);
    }

    @Test
    void fetch_contextIsOmittedWhenTheDisplayNameIsBlank() {
        // A fresh account has no display name at all, and `@acct` is the only
        // identity — there is nothing for the slot to add.
        String page = """
                [{"id":"117144208605002329","created_at":"2026-08-23T09:52:48.000Z",
                 "language":"en","spoiler_text":"","sensitive":false,"reblog":null,
                 "url":"https://mstdn.social/@jane/117144208605002329",
                 "uri":"https://mstdn.social/users/jane/statuses/117144208605002329",
                 "content":"<p>a post</p>",
                 "account":{"acct":"jane","display_name":"","bot":false},
                 "media_attachments":[],"tags":[],
                 "replies_count":0,"reblogs_count":0,"favourites_count":0,"edited_at":null}]
                """;
        http.replyAny(200, page);

        FeedItem item = instance().fetch(
                fetch("public:all", null, FeedDirection.OLDER, 40)).items().get(0);

        assertThat(item.extras()).doesNotContainKey(MastodonFeedInstance.EXTRA_CONTEXT);
    }

    @Test
    void fetch_titleIsTheOpeningOfTheTextCutAtAWord() {
        http.replyAny(200, PAGE);

        FeedItem first = instance().fetch(
                fetch("public:all", null, FeedDirection.OLDER, 40)).items().get(0);

        assertThat(first.title()).endsWith("…");
        assertThat(first.title().length()).isLessThanOrEqualTo(
                MastodonFeedInstance.TITLE_CHARS + 1);
        assertThat(first.title()).doesNotContain("  ");
    }

    @Test
    void fetch_aPostWithoutTextIsTitledByItsAuthorAndMedium() {
        http.replyAny(200, PAGE);

        // Measured: 1 in 40. A FeedItem without a title falls back to the URL,
        // which reads as a line of noise in a timeline.
        FeedItem mediaOnly = instance().fetch(
                fetch("public:all", null, FeedDirection.OLDER, 40)).items().get(3);

        assertThat(mediaOnly.title()).isEqualTo("@LeatherBoyDavid@woof.group · image");
        assertThat(mediaOnly.body()).isNull();
        assertThat(mediaOnly.imageUrl()).isEqualTo("https://files.mstdn.social/small/def.jpg");
        assertThat(mediaOnly.extras()).containsEntry("sensitive", true);
    }

    @Test
    void fetch_contentWarningBecomesTheTitleAndIsNotRepeated() {
        http.replyAny(200, PAGE);

        FeedItem warned = last(instance().fetch(
                fetch("public:all", null, FeedDirection.OLDER, 40)).items());

        // The author wrote the CW as a one-line stand-in for the body, so it is
        // the headline — and printing it again as the summary is what the
        // browser showed: the same sentence twice on one card.
        assertThat(warned.title()).isEqualTo("NSFW 18+ Nudity");
        assertThat(warned.summary()).isNull();
        assertThat(warned.body()).isEqualTo("text behind the warning");
        assertThat(warned.extras()).containsEntry("sensitive", true);
    }

    @Test
    void fetch_aStatusWithoutAUrlFallsBackToItsUri() {
        http.replyAny(200, PAGE);

        // `url` is nullable in the API; `uri` is the ActivityPub id and always
        // there. A FeedItem needs a link, and dropping an otherwise fine entry
        // over a missing one would lose it silently.
        FeedItem warned = last(instance().fetch(
                fetch("public:all", null, FeedDirection.OLDER, 40)).items());

        assertThat(warned.url())
                .isEqualTo("https://xscape.zclan.cc/objects/c93fa57e-69c7-4809-8ac5-dbd9b0a34fd5");
    }

    @Test
    void fetch_anEntryWithoutALanguageKeepsItNull() {
        http.replyAny(200, PAGE);

        // The RSS bridges are exactly the accounts that fill a news feed, and
        // exactly the ones without a language. A null here is what lets the
        // post-filter pass them instead of emptying the stream.
        FeedItem bridged = instance().fetch(
                fetch("public:all", null, FeedDirection.OLDER, 40)).items().get(4);

        assertThat(bridged.language()).isNull();
        assertThat(bridged.body()).isEqualTo(
                "Attacco con spada alla scuola di Fagersta\n\n"
                        + "La vittima è una ragazza di 17 anni");
    }

    @Test
    void fetch_editedStatusKeepsItsOriginalPosition() {
        http.replyAny(200, PAGE);

        // edited_at is ignored: an edited post keeps its id and therefore its
        // place. Sorting it to the top would break the ordering the cursor
        // relies on.
        FeedItem edited = instance().fetch(
                fetch("public:all", null, FeedDirection.OLDER, 40)).items().get(2);

        assertThat(edited.publishedAt()).isEqualTo(Instant.parse("2026-08-23T09:52:46.640Z"));
    }

    // ── paging ───────────────────────────────────────────────────────

    @Test
    void fetch_olderDirectionResumesAfterTheOldestEntry() {
        http.replyAny(200, PAGE);

        FeedPage page = instance().fetch(fetch("public:all", null, FeedDirection.OLDER, 40));

        assertThat(page.nextCursor()).isEqualTo("117144207100000000");
        assertThat(page.hasMore()).isTrue();
    }

    @Test
    void fetch_newerDirectionResumesAfterTheNewestEntry() {
        http.replyAny(200, PAGE);

        // min_id returns the block immediately ABOVE the anchor, still
        // newest-first. Anchoring on the last entry would re-walk the same
        // block forever.
        FeedPage page = instance().fetch(fetch("public:all", null, FeedDirection.NEWER, 40));

        assertThat(page.nextCursor()).isEqualTo("117144208605002329");
    }

    @Test
    void fetch_anEmptyPageRetiresTheStream() {
        http.replyAny(200, "[]");

        FeedPage page = instance().fetch(fetch("public:all", null, FeedDirection.OLDER, 40));

        assertThat(page.items()).isEmpty();
        // No cursor and no claim of more: a stream that says "more" without a
        // way forward makes the merge ask forever.
        assertThat(page.nextCursor()).isNull();
        assertThat(page.hasMore()).isFalse();
    }

    // ── failures ─────────────────────────────────────────────────────

    @Test
    void fetch_aClosedTimelineSaysWhatToDoAboutIt() {
        http.replyAny(422, CLOSED_TIMELINE);

        // Measured: Mastodon answers 422 (GoToSocial 401) and it is per
        // endpoint — mastodon.social serves hashtag: and refuses public:. A
        // bare "HTTP 422" sends the operator hunting for a selector bug.
        assertThatThrownBy(() -> instance().fetch(
                fetch("public:all", null, FeedDirection.OLDER, 40)))
                .isInstanceOf(FeedException.class)
                .hasMessageContaining("without a token")
                .hasMessageContaining("apiKey")
                .hasMessageContaining("hashtag:");
    }

    @Test
    void fetch_rateLimitIsNamedAsSuch() {
        http.replyAny(429, "");

        assertThatThrownBy(() -> instance().fetch(
                fetch("public:all", null, FeedDirection.OLDER, 40)))
                .isInstanceOf(FeedException.class)
                .hasMessageContaining("rate limited");
    }

    @Test
    void fetch_anUnparseableSelectorIsRefusedBeforeAnyCall() {
        assertThatThrownBy(() -> instance().fetch(
                fetch("nonsense", null, FeedDirection.OLDER, 40)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fetch_aNonArrayBodyIsAFailureRatherThanAnEmptyPage() {
        http.replyAny(200, "{\"error\":\"nope\"}");

        assertThatThrownBy(() -> instance().fetch(
                fetch("public:all", null, FeedDirection.OLDER, 40)))
                .isInstanceOf(FeedException.class)
                .hasMessageContaining("expected a JSON array");
    }

    @Test
    void protocol_refusesAnEndpointWithoutABaseUrl() {
        assertThatThrownBy(() -> new MastodonFeedProtocol(http, JsonMapper.builder().build())
                .instantiate(new FeedInstanceConfig(
                        "mastodon-1", MastodonFeedProtocol.ID, "", "", () -> null, Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl");
    }

    @Test
    void instance_namesItselfByHost() {
        assertThat(instance().displayName()).isEqualTo("Mastodon (mstdn.social)");
        assertThat(instance().baseUrl()).isEqualTo(BASE);
    }

    // ── helpers ──────────────────────────────────────────────────────

    private FeedSourceInstance instance() {
        return withToken(null);
    }

    private FeedSourceInstance withToken(String token) {
        return new MastodonFeedProtocol(http, JsonMapper.builder().build())
                .instantiate(new FeedInstanceConfig(
                        "mastodon-1", MastodonFeedProtocol.ID, BASE + "/", "",
                        () -> token, Map.of()));
    }

    private static FeedItem last(List<FeedItem> items) {
        return items.get(items.size() - 1);
    }

    private static FeedFetch fetch(
            String selector, String cursor, FeedDirection direction, int limit) {
        return new FeedFetch(selector, cursor, direction, limit, FeedFilter.none(), null);
    }
}
