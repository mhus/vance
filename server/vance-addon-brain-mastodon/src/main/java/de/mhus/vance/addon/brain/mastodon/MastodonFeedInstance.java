package de.mhus.vance.addon.brain.mastodon;

import de.mhus.vance.brain.centauri.protocols.CentauriHttpClient;
import de.mhus.vance.toolpack.feed.FeedCapabilities;
import de.mhus.vance.toolpack.feed.FeedDirection;
import de.mhus.vance.toolpack.feed.FeedException;
import de.mhus.vance.toolpack.feed.FeedExtraField;
import de.mhus.vance.toolpack.feed.FeedFetch;
import de.mhus.vance.toolpack.feed.FeedContentPolicy;
import de.mhus.vance.toolpack.feed.FeedInstanceConfig;
import de.mhus.vance.toolpack.feed.FeedItem;
import de.mhus.vance.toolpack.feed.FeedPage;
import de.mhus.vance.toolpack.feed.FeedSelector;
import de.mhus.vance.toolpack.feed.FeedSelectorKind;
import de.mhus.vance.toolpack.feed.FeedSelectorMode;
import de.mhus.vance.toolpack.feed.FeedSourceInstance;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One Mastodon server's public and hashtag timelines.
 *
 * <p>What this source exercises that the others do not: {@code FREEFORM}
 * selectors (see {@link MastodonSelector}), the {@code supportsNewerDirection}
 * flag, and a {@code publishedAt} that is <b>not</b> the entry's own timestamp
 * (see {@link MastodonStreamTime} — the measured reason).
 *
 * <p>A toot has no title, so one is derived (§5 of the plan): the content
 * warning when the author wrote one, else the opening of the text, else the
 * media, else the handle. {@code FeedItem} would otherwise fall back to the URL,
 * which reads as a line of noise in a timeline.
 */
@Slf4j
class MastodonFeedInstance implements FeedSourceInstance {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** Hard API ceiling on {@code limit}: asking for more is silently capped. */
    static final int MAX_PAGE_SIZE = 40;

    /** Title length before the opening of a post gets an ellipsis. */
    static final int TITLE_CHARS = 80;

    static final String PATH_PUBLIC = "/api/v1/timelines/public";
    static final String PATH_TAG = "/api/v1/timelines/tag/";

    static final String USER_AGENT = "Vancetope-Centauri/1.0 (+https://github.com/mhus/vance)";

    /** Extras key: the author's own timestamp, which is not {@code publishedAt}. */
    static final String EXTRA_AUTHORED_AT = "authoredAt";

    /**
     * Below this, {@code created_at} and the ingest time are the same event and
     * carrying both would only add a duplicate line to every card.
     */
    static final Duration AUTHORED_AT_NOISE = Duration.ofMinutes(1);

    private final FeedInstanceConfig cfg;
    private final CentauriHttpClient http;
    private final ObjectMapper mapper;
    private final String site;

    MastodonFeedInstance(FeedInstanceConfig cfg, CentauriHttpClient http, ObjectMapper mapper) {
        this.cfg = cfg;
        this.http = http;
        this.mapper = mapper;
        this.site = StringUtils.removeEnd(cfg.baseUrl().trim(), "/");
    }

    @Override
    public String id() {
        return cfg.instanceId();
    }

    @Override
    public String displayName() {
        return "Mastodon (" + host() + ")";
    }

    @Override
    public String baseUrl() {
        return site;
    }

    @Override
    public FeedCapabilities capabilities() {
        return new FeedCapabilities(
                FeedSelectorMode.FREEFORM,
                Set.of(FeedSelectorKind.HASHTAG, FeedSelectorKind.PUBLIC),
                // Status search needs an ElasticSearch backend AND an
                // authenticated user ("full text search is not available to
                // unauthenticated users"), so this stays local.
                /* text */ false,
                // Statuses carry `language`, the timelines do not filter by it.
                /* language */ false,
                // since_id/min_id are ids, not timestamps — there is no time
                // bound to push down.
                /* since */ false,
                /* newer */ true,
                // A toot is complete in the timeline response; there is no
                // "read more" to fetch.
                /* fullBody */ true,
                MAX_PAGE_SIZE,
                // No signals: POST /api/v1/reports needs a user token, which is
                // an act under someone's name and out of this SPI by the §11
                // test.
                Set.of(),
                // A status by a remote account has its `url` on that account's
                // server, so the host-match hardening would drop the deep link
                // for half the entries. url is the link that exists anyway.
                /* controlUrl */ false,
                FeedCapabilities.DEFAULT_TTL,
                List.of(),
                List.of(
                        FeedExtraField.of(EXTRA_AUTHORED_AT, "Written"),
                        FeedExtraField.of("replies", "Replies"),
                        FeedExtraField.of("reblogs", "Boosts"),
                        FeedExtraField.of("favourites", "Favourites")));
    }

    /**
     * Empty: the streams of this source are typed, not enumerated. Offering a
     * list would mean claiming to know every hashtag.
     */
    @Override
    public List<FeedSelector> listSelectors() {
        return List.of();
    }

    /**
     * Policy off this source's own document — see {@link FeedContentPolicy}.
     * One line per protocol on purpose: the merge asks and applies, so the
     * filtering itself lives in one place.
     */
    @Override
    public FeedContentPolicy contentPolicy() {
        return FeedContentPolicy.from(cfg);
    }

    @Override
    public Optional<String> validateSelector(String raw) {
        return MastodonSelector.complain(raw);
    }

    @Override
    public FeedPage fetch(FeedFetch request) {
        MastodonSelector selector = MastodonSelector.parse(request.selector());
        boolean newer = request.direction() == FeedDirection.NEWER;

        Map<String, String> params = CentauriHttpClient.params();
        params.put("limit", String.valueOf(Math.min(request.limit(), MAX_PAGE_SIZE)));
        if (StringUtils.isNotBlank(request.cursor())) {
            // Both are exclusive, so the anchor is never repeated and there is
            // no anchor to drop from the response.
            params.put(newer ? "min_id" : "max_id", request.cursor());
        }
        if (selector.kind() == MastodonSelector.Kind.PUBLIC) {
            switch (selector.value()) {
                case MastodonSelector.PUBLIC_LOCAL -> params.put("local", "true");
                case MastodonSelector.PUBLIC_REMOTE -> params.put("remote", "true");
                default -> { /* public:all — the unfiltered federated timeline */ }
            }
        }

        URI url = CentauriHttpClient.withQuery(pathFor(selector), params);
        JsonNode root = getJson(url);
        if (!root.isArray()) {
            throw new FeedException("expected a JSON array of statuses from " + url
                    + ", got " + root.getNodeType());
        }

        List<FeedItem> items = new ArrayList<>();
        // The stream time of the entry before this one, so a fallback timestamp
        // cannot make the page rise. Null for the first entry.
        @Nullable Instant previous = null;
        // Progress is a property of the RESPONSE, not of what survived mapping.
        // A status without id/url/created_at is skipped, and if a whole charge
        // consisted of those, deriving the cursor from `items` would yield none
        // — the stream would report "end reached" because of a handful of
        // malformed entries rather than because the timeline ran out.
        String firstId = null;
        String lastId = null;
        int received = 0;
        for (JsonNode status : root) {
            received++;
            String statusId = status.path("id").asString("");
            if (!statusId.isBlank()) {
                if (firstId == null) firstId = statusId;
                lastId = statusId;
            }
            FeedItem item = toItem(status, previous);
            if (item != null) {
                items.add(item);
                previous = item.publishedAt();
            }
        }

        // Mastodon has no "there is more" flag, and going back a public
        // timeline is effectively endless — an empty response is the only
        // statement that the end was reached.
        String cursor = newer ? firstId : lastId;
        return new FeedPage(items, cursor, received > 0);
    }

    // Which end of the page is the anchor depends on the direction, because the
    // API sorts newest-first either way. With max_id the page walks backwards
    // and the oldest entry is last, so that one anchors. With min_id the API
    // returns the block immediately ABOVE the anchor, still newest-first — so
    // continuing upwards anchors on the FIRST entry; taking the last one there
    // would hand back an id barely above where the page started and walk the
    // same block again. Both ends are read off the raw response above, so a
    // skipped status still moves the cursor past itself.

    // ── mapping ──────────────────────────────────────────────────────

    private @Nullable FeedItem toItem(JsonNode status, @Nullable Instant previous) {
        String id = status.path("id").asString("");
        // `url` is nullable in the API; `uri` is the ActivityPub id and always
        // present. A FeedItem needs a link, and this is the fallback that keeps
        // an otherwise fine entry.
        String link = firstNonBlank(status.path("url").asString(""),
                status.path("uri").asString(""));
        Instant createdAt = parseInstant(status.path("created_at").asString(""));
        if (id.isBlank() || link.isBlank() || createdAt == null) {
            log.warn("Centauri/mastodon '{}': skipping status without id, url or created_at",
                    cfg.instanceId());
            return null;
        }

        String text = StatusHtml.toText(status.path("content").asString(""));
        // spoiler_text is plain text in the API, not HTML — only whitespace to
        // tidy, nothing to parse.
        String spoiler = StatusHtml.collapse(status.path("spoiler_text").asString(""));
        List<String> tags = new ArrayList<>();
        for (JsonNode tag : status.path("tags")) {
            String name = tag.path("name").asString("");
            if (!name.isBlank()) {
                tags.add(name);
            }
        }

        Instant publishedAt = MastodonStreamTime.of(id, createdAt, previous);
        Map<String, Object> extras = new LinkedHashMap<>();
        // The author's own timestamp, but only when it actually differs:
        // publishedAt is the ingest time, and for a bridged article that can be
        // a day and a half earlier — there a card saying "5 minutes ago" would
        // be a lie. For the great majority the two agree within seconds, and a
        // second identical timestamp on every card is noise that would teach
        // the reader to ignore the row that matters.
        if (Duration.between(createdAt, publishedAt).abs().compareTo(AUTHORED_AT_NOISE) > 0) {
            extras.put(EXTRA_AUTHORED_AT, createdAt.toString());
        }
        extras.put("replies", status.path("replies_count").asLong(0L));
        extras.put("reblogs", status.path("reblogs_count").asLong(0L));
        extras.put("favourites", status.path("favourites_count").asLong(0L));
        if (status.path("sensitive").asBoolean(false)) {
            extras.put("sensitive", true);
        }
        String acct = status.path("account").path("acct").asString("");
        if (!acct.isBlank()) {
            extras.put("account", "@" + acct);
        }

        return new FeedItem(
                id,
                // No separate cursor token: for these timelines the entry id IS
                // the paging anchor, so the SPI default (id when cursor is
                // null) is right. A boosted entry would need one — its content
                // id differs from the timeline id — but public and hashtag
                // timelines carry no boosts at all (measured: 0 in 160).
                /* cursor */ null,
                publishedAt,
                title(spoiler, text, status, acct),
                link,
                // No summary. A toot has a title and a body and nothing in
                // between: without a content warning a summary would repeat the
                // opening of the body, and with one it would repeat the title,
                // which is the content warning. Seen in the browser as the same
                // sentence printed twice on a CW card.
                /* summary */ null,
                text.isEmpty() ? null : text,
                acct.isBlank() ? null : "@" + acct,
                blankToNull(status.path("language").asString("")),
                previewUrl(status),
                /* controlUrl */ null,
                tags,
                extras);
    }

    /**
     * A toot has no title. In order of preference: the content warning, because
     * the author wrote it as a one-line stand-in for the body; the opening of
     * the text; the description of the first attachment; the handle.
     */
    private static String title(
            String spoiler, String text, JsonNode status, String acct) {
        if (!spoiler.isBlank()) {
            return truncate(spoiler);
        }
        String line = StatusHtml.collapse(text);
        if (!line.isBlank()) {
            return truncate(line);
        }
        for (JsonNode media : status.path("media_attachments")) {
            String description = media.path("description").asString("");
            if (!description.isBlank()) {
                return truncate(description.strip());
            }
        }
        // Measured: 1 in 40 entries has no text at all, and attachment
        // descriptions are usually absent — so this branch is reached, and
        // "@someone · image" beats a raw URL as a headline.
        String kind = status.path("media_attachments").isEmpty()
                ? (status.path("poll").isMissingNode() || status.path("poll").isNull()
                        ? "" : " · poll")
                : " · " + status.path("media_attachments").path(0).path("type").asString("media");
        return acct.isBlank() ? "(no text)" : "@" + acct + kind;
    }

    private static @Nullable String previewUrl(JsonNode status) {
        for (JsonNode media : status.path("media_attachments")) {
            String preview = firstNonBlank(
                    media.path("preview_url").asString(""), media.path("url").asString(""));
            if (!preview.isBlank()) {
                return preview;
            }
        }
        return null;
    }

    // ── internals ────────────────────────────────────────────────────

    private String pathFor(MastodonSelector selector) {
        return selector.kind() == MastodonSelector.Kind.HASHTAG
                ? site + PATH_TAG + urlEncode(selector.value())
                : site + PATH_PUBLIC;
    }

    private JsonNode getJson(URI url) {
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", USER_AGENT);
            String credential = cfg.credential();
            if (StringUtils.isNotBlank(credential)) {
                headers.put("Authorization", "Bearer " + credential.trim());
            }
            CentauriHttpClient.Response response = http.get(url, headers, TIMEOUT);
            if (!response.isSuccess()) {
                throw new FeedException(describeFailure(response, url));
            }
            return mapper.readTree(response.body());
        } catch (FeedException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FeedException("interrupted while calling " + url, e);
        } catch (Exception e) {
            throw new FeedException("could not read " + url + ": " + e, e);
        }
    }

    /**
     * Name the remedy when the server is asking for a token.
     *
     * <p>Measured: Mastodon answers a closed timeline with <b>422</b> and
     * {@code {"error":"This method requires an authenticated user"}},
     * GoToSocial with <b>401</b> — and it is per endpoint, so mastodon.social
     * serves {@code hashtag:} while refusing {@code public:}. A bare "HTTP 422"
     * sends the operator looking for a bug in the selector.
     *
     * <p>This shapes the message only. Whether such a failure should skip the
     * cooldown — it is configuration, not an outage — is an open point in
     * {@code planning/centauri-mastodon.md} §10 and belongs to the gate, which
     * is shared by every protocol.
     */
    private String describeFailure(CentauriHttpClient.Response response, URI url) {
        int status = response.statusCode();
        if (status == 401 || status == 403 || status == 422) {
            return "HTTP " + status + " from " + url
                    + " — this server does not serve that timeline without a token."
                    + " Set apiKey in _vance/config/feeds/" + cfg.instanceId()
                    + ".yaml to an app token, or use a hashtag: selector,"
                    + " which many servers keep open. Response: "
                    + StatusHtml.collapse(StringUtils.abbreviate(response.body(), 200));
        }
        if (status == 429) {
            return "HTTP 429 from " + url + " — rate limited (300 requests per 5 minutes"
                    + " per IP on a default Mastodon)";
        }
        return "HTTP " + status + " from " + url;
    }

    private String host() {
        try {
            String host = URI.create(site).getHost();
            return host == null ? site : host;
        } catch (RuntimeException e) {
            return site;
        }
    }

    private static @Nullable Instant parseInstant(String raw) {
        if (raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String truncate(String text) {
        String single = text.strip();
        if (single.length() <= TITLE_CHARS) {
            return single;
        }
        String head = single.substring(0, TITLE_CHARS);
        int space = head.lastIndexOf(' ');
        // Only cut at a word when that leaves something worth reading; CJK text
        // has no spaces at all and would otherwise lose the whole line.
        if (space > TITLE_CHARS / 2) {
            head = head.substring(0, space);
        }
        return head.stripTrailing() + "…";
    }

    private static String firstNonBlank(String a, String b) {
        return a.isBlank() ? b : a;
    }

    private static @Nullable String blankToNull(String s) {
        return s.isBlank() ? null : s;
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
