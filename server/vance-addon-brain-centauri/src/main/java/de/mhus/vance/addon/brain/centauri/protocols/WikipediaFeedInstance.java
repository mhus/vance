package de.mhus.vance.addon.brain.centauri.protocols;

import de.mhus.vance.brain.centauri.protocols.CentauriHttpClient;
import de.mhus.vance.toolpack.feed.FeedCapabilities;
import de.mhus.vance.toolpack.feed.FeedDirection;
import de.mhus.vance.toolpack.feed.FeedException;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Recent changes of one wiki, via the Action API.
 *
 * <p>The contract detail this source exercises that USGS does not is a real
 * <b>{@code controlUrl}</b> — the diff page, which is the source's own UI for
 * exactly this entry, as opposed to {@code url} which points at the article.
 *
 * <p><b>Paging is derived, not delegated.</b> The API's {@code rccontinue} is
 * read only as a „there is more" flag; the cursor is rebuilt from the last
 * entry actually delivered. The token names the first entry of the <em>next</em>
 * batch, so treating it as a cursor made the anchor mean two different things
 * and silently dropped one change per page boundary. See {@link AnchoredCursor}
 * for what the anchor is for.
 */
@Slf4j
class WikipediaFeedInstance implements FeedSourceInstance {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /**
     * Wikimedia policy: a descriptive User-Agent naming the project and a
     * contact. Generic agents are blocked outright.
     */
    static final String USER_AGENT =
            "Vancetope-Centauri/1.0 (https://github.com/mhus/vance)";

    /** {@code rccontinue} packs the timestamp in this compact form. */
    private static final DateTimeFormatter COMPACT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    static final String SELECTOR_ALL = "all";

    /** Namespace per selector; absent means "every namespace". */
    private static final Map<String, String> NAMESPACES = Map.of(
            "article", "0",
            "talk", "1",
            "category", "14",
            "template", "10");

    private static final List<FeedSelector> SELECTORS = List.of(
            FeedSelector.of(SELECTOR_ALL, "All changes", FeedSelectorKind.CATEGORY),
            FeedSelector.of("article", "Articles", FeedSelectorKind.CATEGORY),
            FeedSelector.of("talk", "Talk pages", FeedSelectorKind.CATEGORY),
            FeedSelector.of("category", "Categories", FeedSelectorKind.CATEGORY),
            FeedSelector.of("template", "Templates", FeedSelectorKind.CATEGORY));

    private final FeedInstanceConfig cfg;
    private final CentauriHttpClient http;
    private final ObjectMapper mapper;
    private final String site;
    private final String language;

    WikipediaFeedInstance(FeedInstanceConfig cfg, CentauriHttpClient http, ObjectMapper mapper) {
        this.cfg = cfg;
        this.http = http;
        this.mapper = mapper;
        this.site = StringUtils.removeEnd(cfg.baseUrl(), "/");
        this.language = cfg.extra(WikipediaFeedProtocol.EXTRA_LANGUAGE, languageFromHost(site));
    }

    @Override
    public String id() {
        return cfg.instanceId();
    }

    @Override
    public String displayName() {
        return "Wikipedia (" + language + ")";
    }

    @Override
    public String baseUrl() {
        return site;
    }

    @Override
    public FeedCapabilities capabilities() {
        return new FeedCapabilities(
                FeedSelectorMode.ENUMERABLE,
                Set.of(FeedSelectorKind.CATEGORY),
                /* text */ false,
                // One wiki is one language, so there is nothing to filter within
                // it — the entries carry their language and Centauri's
                // post-filter does the rest.
                /* language */ false,
                /* since */ true,
                /* newer */ true,
                /* fullBody */ true,
                500,
                Set.of(),
                /* controlUrl */ true,
                Duration.ofHours(24));
    }

    @Override
    public List<FeedSelector> listSelectors() {
        return SELECTORS;
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
    public FeedPage fetch(FeedFetch request) {
        AnchoredCursor cursor = AnchoredCursor.parse(request.cursor());
        boolean newer = request.direction() == FeedDirection.NEWER;

        Map<String, String> params = CentauriHttpClient.params();
        params.put("action", "query");
        params.put("list", "recentchanges");
        params.put("format", "json");
        params.put("formatversion", "2");
        params.put("rcprop", "title|timestamp|ids|user|comment|sizes|flags");
        params.put("rclimit", String.valueOf(request.limit()));
        params.put("rcdir", newer ? "newer" : "older");
        String namespace = NAMESPACES.get(request.selector().trim().toLowerCase(Locale.ROOT));
        if (namespace != null) {
            params.put("rcnamespace", namespace);
        }
        if (cursor != null) {
            params.put("rccontinue", cursor.encode());
        }
        if (request.pushdown().since() != null) {
            // With rcdir=older the older bound is rcend; walking forwards it is
            // rcstart. Same wall, opposite side.
            params.put(newer ? "rcstart" : "rcend", request.pushdown().since().toString());
        }

        URI url = CentauriHttpClient.withQuery(site + WikipediaFeedProtocol.API_PATH, params);
        JsonNode root = getJson(url);
        failOnApiError(root, url);

        List<FeedItem> raw = new ArrayList<>();
        for (JsonNode change : root.path("query").path("recentchanges")) {
            FeedItem item = toItem(change);
            if (item != null) {
                raw.add(item);
            }
        }
        List<FeedItem> items = AnchoredCursor.dropAnchor(raw, cursor);

        // The API's continue token says whether more exists, and nothing else.
        //
        // It is NOT used as the cursor, and that is the correction: it names the
        // *first entry of the next batch* — an entry this page never delivered.
        // Handed back it returns that entry, which is right, and then
        // dropAnchor would remove it as the anchor, which loses one change at
        // every page boundary. Deriving the cursor from the last entry we did
        // deliver has the anchor mean the one thing it can mean everywhere:
        // "already shown, drop the repeat". Same shape as the USGS adapter.
        String continueToken = root.path("continue").path("rccontinue").asString("");
        return new FeedPage(items, nextCursor(items), StringUtils.isNotBlank(continueToken));
    }

    /**
     * {@code rccontinue} is {@code <compact timestamp>|<rcid>}, so a cursor can
     * be rebuilt from a single entry — which is what a page cut in the middle
     * needs, and here what every page uses. It is inclusive of the entry it
     * names, hence the anchor.
     */
    @Override
    public String cursorAfter(FeedItem item) {
        return new AnchoredCursor(COMPACT.format(item.publishedAt()), item.id()).encode();
    }

    /**
     * Resume after the last entry actually delivered, or nowhere.
     *
     * <p>Null on an empty page rather than the API's token: with nothing
     * delivered there is nothing to resume after, and the merge treats a stream
     * that claims more without a way forward as retired instead of asking again
     * forever. Reaching that state needs an empty batch alongside a continue
     * token, which the API does not produce for this query — and if it ever
     * does, one retired stream with a log line beats a spinning scroll.
     */
    private @Nullable String nextCursor(List<FeedItem> items) {
        return items.isEmpty() ? null : cursorAfter(items.get(items.size() - 1));
    }

    // ── internals ────────────────────────────────────────────────────

    private @Nullable FeedItem toItem(JsonNode change) {
        long rcid = change.path("rcid").asLong(0L);
        String title = change.path("title").asString("");
        Instant timestamp = parseTimestamp(change.path("timestamp").asString(""));
        if (rcid <= 0 || title.isBlank() || timestamp == null) {
            log.warn("Centauri/wikipedia '{}': skipping change without rcid, title or timestamp",
                    cfg.instanceId());
            return null;
        }

        long revid = change.path("revid").asLong(0L);
        long oldRevid = change.path("old_revid").asLong(0L);
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("rcid", rcid);
        extras.put("namespace", change.path("ns").asInt(0));
        if (revid > 0) {
            extras.put("revisionId", revid);
        }
        long delta = change.path("newlen").asLong(0L) - change.path("oldlen").asLong(0L);
        extras.put("sizeDelta", delta);

        List<String> tags = new ArrayList<>(3);
        tags.add(change.path("type").asString("edit"));
        if (change.path("bot").asBoolean(false)) {
            tags.add("bot");
        }
        if (change.path("minor").asBoolean(false)) {
            tags.add("minor");
        }

        return new FeedItem(
                String.valueOf(rcid),
                // Cursor derived in cursorAfter(), not carried per item: this
                // adapter knows its own paging scheme.
                /* cursor */ null,
                timestamp,
                title,
                articleUrl(title),
                summary(change, delta),
                /* body */ null,
                blankToNull(change.path("user").asString("")),
                language,
                /* imageUrl */ null,
                // The diff is the wiki's own view of exactly this change — a
                // genuine controlUrl, as opposed to url which is the article.
                revid > 0 ? diffUrl(revid, oldRevid) : null,
                tags,
                extras);
    }

    private static @Nullable String summary(JsonNode change, long delta) {
        String comment = change.path("comment").asString("").trim();
        String sign = delta > 0 ? "+" : "";
        String size = "(" + sign + delta + ")";
        return comment.isEmpty() ? size : size + " " + comment;
    }

    private String articleUrl(String title) {
        return site + "/wiki/" + urlEncode(title.replace(' ', '_'));
    }

    private String diffUrl(long revid, long oldRevid) {
        String base = site + WikipediaFeedProtocol.API_PATH.replace("api.php", "index.php")
                + "?diff=" + revid;
        return oldRevid > 0 ? base + "&oldid=" + oldRevid : base;
    }

    /**
     * The Action API answers a rejected request with HTTP 200 and an
     * {@code error} object, so the status alone is not enough to tell success
     * from failure.
     */
    private void failOnApiError(JsonNode root, URI url) {
        JsonNode error = root.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new FeedException("MediaWiki API error from " + url + ": "
                    + error.path("code").asString("unknown") + " — "
                    + error.path("info").asString(""));
        }
    }

    private static @Nullable Instant parseTimestamp(String raw) {
        if (raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** {@code https://de.wikipedia.org} yields {@code de}. */
    static String languageFromHost(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            if (host == null) {
                return "";
            }
            int dot = host.indexOf('.');
            return dot > 0 ? host.substring(0, dot).toLowerCase(Locale.ROOT) : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private JsonNode getJson(URI url) {
        try {
            CentauriHttpClient.Response response = http.get(
                    url, Map.of("User-Agent", USER_AGENT), TIMEOUT);
            if (!response.isSuccess()) {
                throw new FeedException("HTTP " + response.statusCode() + " from " + url);
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

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static @Nullable String blankToNull(String s) {
        return s.isBlank() ? null : s;
    }
}
