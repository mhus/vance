package de.mhus.vance.brain.centauri.protocols;

import de.mhus.vance.toolpack.feed.FeedCapabilities;
import de.mhus.vance.toolpack.feed.FeedDirection;
import de.mhus.vance.toolpack.feed.FeedException;
import de.mhus.vance.toolpack.feed.FeedFetch;
import de.mhus.vance.toolpack.feed.FeedInstanceConfig;
import de.mhus.vance.toolpack.feed.FeedItem;
import de.mhus.vance.toolpack.feed.FeedPage;
import de.mhus.vance.toolpack.feed.FeedSelector;
import de.mhus.vance.toolpack.feed.FeedSelectorKind;
import de.mhus.vance.toolpack.feed.FeedSelectorMode;
import de.mhus.vance.toolpack.feed.FeedSourceInstance;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
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
 * One USGS event-service instance. Anonymous, unauthenticated, and paged by
 * time.
 */
@Slf4j
class UsgsFeedInstance implements FeedSourceInstance {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** Selector meaning "no magnitude floor". */
    static final String SELECTOR_ALL = "all";

    private static final List<FeedSelector> SELECTORS = List.of(
            FeedSelector.of(SELECTOR_ALL, "All earthquakes", FeedSelectorKind.CATEGORY),
            FeedSelector.of("m2.5", "Magnitude 2.5+", FeedSelectorKind.CATEGORY),
            FeedSelector.of("m4.5", "Magnitude 4.5+", FeedSelectorKind.CATEGORY),
            FeedSelector.of("m6", "Magnitude 6+ (significant)", FeedSelectorKind.CATEGORY));

    private final FeedInstanceConfig cfg;
    private final CentauriHttpClient http;
    private final ObjectMapper mapper;
    private final String queryUrl;

    UsgsFeedInstance(FeedInstanceConfig cfg, CentauriHttpClient http, ObjectMapper mapper) {
        this.cfg = cfg;
        this.http = http;
        this.mapper = mapper;
        String base = StringUtils.defaultIfBlank(
                StringUtils.removeEnd(cfg.baseUrl(), "/"), UsgsFeedProtocol.DEFAULT_BASE_URL);
        this.queryUrl = base + UsgsFeedProtocol.QUERY_PATH;
    }

    @Override
    public String id() {
        return cfg.instanceId();
    }

    @Override
    public String displayName() {
        return "USGS earthquakes";
    }

    @Override
    public String baseUrl() {
        return StringUtils.defaultIfBlank(cfg.baseUrl(), UsgsFeedProtocol.DEFAULT_BASE_URL);
    }

    /**
     * Fixed, and answered without a round trip — what this service can do is a
     * property of the service, not of its current state. The long TTL follows
     * from that.
     */
    @Override
    public FeedCapabilities capabilities() {
        return new FeedCapabilities(
                FeedSelectorMode.ENUMERABLE,
                Set.of(FeedSelectorKind.CATEGORY),
                /* text */ false,
                /* language */ false,
                /* since */ true,
                /* newer */ true,
                // A list entry is everything there is; there is no body to fetch.
                /* fullBody */ true,
                200,
                Set.of(),
                /* controlUrl */ false,
                Duration.ofHours(24));
    }

    @Override
    public List<FeedSelector> listSelectors() {
        return SELECTORS;
    }

    @Override
    public FeedPage fetch(FeedFetch request) {
        AnchoredCursor cursor = AnchoredCursor.parse(request.cursor());
        boolean newer = request.direction() == FeedDirection.NEWER;

        Map<String, String> params = CentauriHttpClient.params();
        params.put("format", "geojson");
        params.put("orderby", newer ? "time-asc" : "time");
        params.put("limit", String.valueOf(request.limit()));
        String magnitude = minMagnitude(request.selector());
        if (magnitude != null) {
            params.put("minmagnitude", magnitude);
        }

        // endtime and starttime are inclusive bounds, so the anchor comes back
        // with the next page and is dropped below.
        String since = request.pushdown().since() == null
                ? null : request.pushdown().since().toString();
        if (newer) {
            params.put("starttime", later(cursor == null ? null : cursor.position(), since));
        } else {
            if (cursor != null) {
                params.put("endtime", cursor.position());
            }
            params.put("starttime", since);
        }

        URI url = CentauriHttpClient.withQuery(queryUrl, params);
        JsonNode root = getJson(url);

        List<FeedItem> raw = new ArrayList<>();
        for (JsonNode feature : root.path("features")) {
            FeedItem item = toItem(feature);
            if (item != null) {
                raw.add(item);
            }
        }
        // The service does not say whether more exists. A full page is the only
        // signal available, and it is a safe one here: a time cursor cannot skip
        // entries, so a wrong "more" costs one empty request and nothing else.
        boolean hasMore = raw.size() >= request.limit();

        List<FeedItem> items = AnchoredCursor.dropAnchor(raw, cursor);
        return new FeedPage(items, nextCursor(items), hasMore);
    }

    /**
     * The cursor is the entry's time plus its id: time because that is what the
     * service pages by, id because the time bound is inclusive and the anchor
     * has to be recognisable in the next page.
     */
    @Override
    public String cursorAfter(FeedItem item) {
        return new AnchoredCursor(item.publishedAt().toString(), item.id()).encode();
    }

    // ── internals ────────────────────────────────────────────────────

    private @Nullable String nextCursor(List<FeedItem> items) {
        if (items.isEmpty()) {
            return null;
        }
        return cursorAfter(items.get(items.size() - 1));
    }

    /** Selector to magnitude floor; null for {@link #SELECTOR_ALL} and unknowns. */
    private static @Nullable String minMagnitude(String selector) {
        String value = selector.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || SELECTOR_ALL.equals(value)) {
            return null;
        }
        String digits = StringUtils.removeStart(value, "m");
        try {
            return String.valueOf(Double.parseDouble(digits));
        } catch (NumberFormatException e) {
            throw new FeedException("unknown USGS selector '" + selector
                    + "' — expected one of " + SELECTORS.stream().map(FeedSelector::value).toList());
        }
    }

    /** The later of two ISO instants, either of which may be absent. */
    private static @Nullable String later(@Nullable String a, @Nullable String b) {
        if (StringUtils.isBlank(a)) {
            return b;
        }
        if (StringUtils.isBlank(b)) {
            return a;
        }
        return Instant.parse(a).isAfter(Instant.parse(b)) ? a : b;
    }

    private @Nullable FeedItem toItem(JsonNode feature) {
        JsonNode properties = feature.path("properties");
        String id = feature.path("id").asString("");
        long time = properties.path("time").asLong(0L);
        String url = properties.path("url").asString("");
        if (id.isBlank() || time <= 0 || url.isBlank()) {
            log.warn("Centauri/usgs '{}': skipping feature without id, time or url", cfg.instanceId());
            return null;
        }

        Map<String, Object> extras = new LinkedHashMap<>();
        putIfPresent(extras, "magnitude", properties.path("mag"));
        putIfPresent(extras, "place", properties.path("place"));
        JsonNode coordinates = feature.path("geometry").path("coordinates");
        if (coordinates.isArray() && coordinates.size() >= 3) {
            extras.put("longitude", coordinates.get(0).asDouble());
            extras.put("latitude", coordinates.get(1).asDouble());
            extras.put("depthKm", coordinates.get(2).asDouble());
        }

        String type = properties.path("type").asString("earthquake");
        return new FeedItem(
                id,
                Instant.ofEpochMilli(time),
                StringUtils.defaultIfBlank(properties.path("title").asString(""), url),
                url,
                summary(properties, coordinates),
                /* body */ null,
                /* author */ null,
                // No language field, deliberately left null — this is the source
                // that keeps the "unknown language passes" rule honest.
                /* language */ null,
                /* imageUrl */ null,
                /* controlUrl */ null,
                List.of(type),
                extras);
    }

    private static @Nullable String summary(JsonNode properties, JsonNode coordinates) {
        List<String> parts = new ArrayList<>(3);
        if (properties.path("mag").isNumber()) {
            parts.add("M " + properties.path("mag").asDouble());
        }
        String place = properties.path("place").asString("");
        if (!place.isBlank()) {
            parts.add(place);
        }
        if (coordinates.isArray() && coordinates.size() >= 3) {
            parts.add(String.format(Locale.ROOT, "depth %.1f km", coordinates.get(2).asDouble()));
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    private static void putIfPresent(Map<String, Object> target, String key, JsonNode node) {
        if (node.isNumber()) {
            target.put(key, node.asDouble());
        } else if (node.isString() && !node.asString("").isBlank()) {
            target.put(key, node.asString(""));
        }
    }

    private JsonNode getJson(URI url) {
        try {
            CentauriHttpClient.Response response = http.get(url, Map.of(), TIMEOUT);
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
}
