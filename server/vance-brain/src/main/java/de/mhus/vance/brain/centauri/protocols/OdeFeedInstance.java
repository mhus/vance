package de.mhus.vance.brain.centauri.protocols;

import de.mhus.vance.toolpack.feed.FeedActor;
import de.mhus.vance.toolpack.facet.Facet;
import de.mhus.vance.toolpack.facet.FacetValue;
import de.mhus.vance.toolpack.feed.FeedCapabilities;
import de.mhus.vance.toolpack.feed.FeedExtraField;
import de.mhus.vance.toolpack.feed.FeedDirection;
import de.mhus.vance.toolpack.feed.FeedException;
import de.mhus.vance.toolpack.feed.FeedFetch;
import de.mhus.vance.toolpack.feed.FeedInstanceConfig;
import de.mhus.vance.toolpack.feed.FeedItem;
import de.mhus.vance.toolpack.feed.FeedPage;
import de.mhus.vance.toolpack.feed.FeedSelector;
import de.mhus.vance.toolpack.feed.FeedSelectorKind;
import de.mhus.vance.toolpack.feed.FeedSelectorMode;
import de.mhus.vance.toolpack.feed.FeedSignal;
import de.mhus.vance.toolpack.feed.FeedSignalOutcome;
import de.mhus.vance.toolpack.feed.FeedSignalRequest;
import de.mhus.vance.toolpack.feed.FeedSourceInstance;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * One configured source that speaks the ode feed contract.
 *
 * <p>Reads the foreign JSON with node navigation rather than mirrored record
 * classes. The alternative would be a third copy of the same shapes — the
 * contract already exists twice on purpose, once in {@code vance-ode-centauri}
 * and once as this side's {@code toolpack.feed} types, and a third would be
 * copy without contract value. Node navigation also tolerates fields a newer
 * source adds, which a strict binding would reject.
 */
@Slf4j
class OdeFeedInstance implements FeedSourceInstance {

    /**
     * Reader pseudonym header, defined by the ode contract
     * ({@code OdeFeedHeaders.READER}).
     *
     * <p>Repeated as a literal because {@code vance-brain} must not depend on
     * {@code vance-ode}: the library exists to be embedded in foreign software,
     * and the brain depending on it would invert that. A shared string across a
     * contract boundary is the cost of the boundary being real.
     */
    static final String HEADER_READER = "X-Vance-Reader";

    private static final Duration TIMEOUT = Duration.ofSeconds(12);

    private final FeedInstanceConfig cfg;
    private final CentauriHttpClient http;
    private final ObjectMapper mapper;
    private final String feedBase;

    OdeFeedInstance(FeedInstanceConfig cfg, CentauriHttpClient http, ObjectMapper mapper) {
        this.cfg = cfg;
        this.http = http;
        this.mapper = mapper;
        this.feedBase = StringUtils.removeEnd(cfg.baseUrl(), "/")
                + cfg.extra(OdeFeedProtocol.EXTRA_FEED_PATH, OdeFeedProtocol.DEFAULT_FEED_PATH);
    }

    @Override
    public String id() {
        return cfg.instanceId();
    }

    @Override
    public String displayName() {
        return cfg.instanceId();
    }

    @Override
    public String baseUrl() {
        return cfg.baseUrl();
    }

    @Override
    public FeedCapabilities capabilities() {
        JsonNode node = getJson(URI.create(feedBase + "/capabilities"), /* actor */ null);
        return new FeedCapabilities(
                enumOf(FeedSelectorMode.class, text(node, "selectorMode"), FeedSelectorMode.NONE),
                enumSet(node.path("selectorKinds"), FeedSelectorKind.class),
                node.path("pushdownTextSearch").asBoolean(false),
                node.path("pushdownLanguage").asBoolean(false),
                node.path("pushdownSince").asBoolean(false),
                node.path("supportsNewerDirection").asBoolean(false),
                node.path("carriesFullBody").asBoolean(false),
                node.path("maxPageSize").asInt(FeedCapabilities.DEFAULT_MAX_PAGE_SIZE),
                enumSet(node.path("signalsAccepted"), FeedSignal.class),
                node.path("carriesControlUrl").asBoolean(false),
                duration(text(node, "capabilitiesTtl")),
                facets(node.path("facets")),
                extraFields(node.path("extraFields")));
    }

    /**
     * The declared facets. A malformed entry is skipped rather than failing
     * the whole declaration — losing one filter is recoverable, losing the
     * source is not.
     */
    private static List<Facet> facets(JsonNode array) {
        if (!array.isArray()) {
            return List.of();
        }
        List<Facet> out = new ArrayList<>(array.size());
        for (JsonNode entry : array) {
            String key = text(entry, "key");
            if (StringUtils.isBlank(key)) {
                continue;
            }
            try {
                out.add(new Facet(
                        key,
                        StringUtils.defaultIfBlank(text(entry, "label"), key),
                        entry.path("hierarchical").asBoolean(false),
                        facetValues(entry.path("values")),
                        entry.path("lazyChildren").asBoolean(false)));
            } catch (IllegalArgumentException e) {
                log.warn("Ode feed endpoint declares unusable facet '{}': {}", key, e.getMessage());
            }
        }
        return List.copyOf(out);
    }

    /**
     * Which extras this source says are worth showing, in its order. A malformed
     * entry is skipped: losing one label is recoverable, losing the source is not.
     */
    private static List<FeedExtraField> extraFields(JsonNode array) {
        if (!array.isArray()) {
            return List.of();
        }
        List<FeedExtraField> out = new ArrayList<>(array.size());
        for (JsonNode entry : array) {
            String key = text(entry, "key");
            if (StringUtils.isBlank(key)) {
                continue;
            }
            out.add(new FeedExtraField(
                    key, StringUtils.defaultIfBlank(text(entry, "label"), key)));
        }
        return List.copyOf(out);
    }

    private static List<FacetValue> facetValues(JsonNode array) {
        if (!array.isArray()) {
            return List.of();
        }
        List<FacetValue> out = new ArrayList<>(array.size());
        for (JsonNode entry : array) {
            String id = text(entry, "id");
            if (StringUtils.isBlank(id)) {
                continue;
            }
            out.add(new FacetValue(
                    id,
                    StringUtils.defaultIfBlank(text(entry, "label"), id),
                    blankToNull(text(entry, "parentId"))));
        }
        return List.copyOf(out);
    }

    @Override
    public List<FacetValue> listFacetValues(String key, @Nullable String parentId) {
        Map<String, String> params = CentauriHttpClient.params();
        params.put("key", key);
        params.put("parent", parentId);
        return facetValues(getJson(
                CentauriHttpClient.withQuery(feedBase + "/facets", params), /* actor */ null));
    }

    @Override
    public List<FeedSelector> listSelectors() {
        JsonNode node = getJson(URI.create(feedBase + "/selectors"), /* actor */ null);
        if (!node.isArray()) {
            return List.of();
        }
        List<FeedSelector> out = new ArrayList<>(node.size());
        for (JsonNode entry : node) {
            String value = text(entry, "value");
            if (StringUtils.isBlank(value)) {
                continue;
            }
            out.add(new FeedSelector(
                    value,
                    StringUtils.defaultIfBlank(text(entry, "label"), value),
                    enumOf(FeedSelectorKind.class, text(entry, "kind"), FeedSelectorKind.CATEGORY),
                    blankToNull(text(entry, "language"))));
        }
        return List.copyOf(out);
    }

    @Override
    public FeedPage fetch(FeedFetch request) {
        Map<String, String> params = CentauriHttpClient.params();
        params.put("selector", request.selector());
        params.put("cursor", request.cursor());
        params.put("direction", request.direction().name());
        params.put("limit", String.valueOf(request.limit()));
        params.put("text", request.pushdown().text());
        if (!request.pushdown().languages().isEmpty()) {
            params.put("languages", String.join(",", request.pushdown().languages()));
        }
        if (request.pushdown().since() != null) {
            params.put("since", request.pushdown().since().toString());
        }

        URI url = CentauriHttpClient.withRepeated(
                CentauriHttpClient.withQuery(feedBase + "/items", params),
                "facet", facetParams(request.pushdown().facets()));
        JsonNode node = getJson(url, request.actor());

        List<FeedItem> items = new ArrayList<>();
        for (JsonNode entry : node.path("items")) {
            FeedItem item = toItem(entry);
            if (item != null) {
                items.add(item);
            }
        }
        return new FeedPage(
                items,
                blankToNull(text(node, "nextCursor")),
                node.path("hasMore").asBoolean(false));
    }

    /**
     * The selection as repeated {@code key:value} parameters. Split at the
     * first colon on the far end, which is why the value keeps its own —
     * {@code origin-place:m49:142} is one key and one value, not three.
     */
    private static List<String> facetParams(Map<String, List<String>> facets) {
        if (facets.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : facets.entrySet()) {
            for (String value : e.getValue()) {
                out.add(e.getKey() + ':' + value);
            }
        }
        return List.copyOf(out);
    }

    @Override
    public Optional<FeedItem> loadItem(String itemId, @Nullable FeedActor actor) {
        URI url = URI.create(feedBase + "/item/" + urlPathSegment(itemId));
        CentauriHttpClient.Response response = call(() -> http.get(url, headers(actor), TIMEOUT), url);
        if (response.statusCode() == 404) {
            // An entry may legitimately have aged out of the stream between the
            // page and the click — that is not a failure of the source.
            return Optional.empty();
        }
        requireSuccess(response, url);
        // Parsed by the same mapper as a page entry, because it is the same
        // shape: a detail that needed its own parser would be a second
        // contract wearing the first one's name.
        return Optional.ofNullable(toItem(parse(response.body(), url)));
    }

    @Override
    public FeedSignalOutcome sendSignal(FeedSignalRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("itemId", request.itemId());
        body.put("signal", request.signal().name());
        if (request.reason() != null) {
            body.put("reason", request.reason().name());
        }
        if (request.requestKind() != null) {
            body.put("requestKind", request.requestKind().name());
        }
        if (request.note() != null) {
            body.put("note", request.note());
        }

        URI url = URI.create(feedBase + "/signal");
        String json = mapper.writeValueAsString(body);
        CentauriHttpClient.Response response = call(
                () -> http.postJson(url, json, headers(request.actor()), TIMEOUT), url);

        // The three statuses the contract assigns meaning to. Anything else is a
        // failure and travels as one, so the failure tracker gets to classify it.
        return switch (response.statusCode()) {
            case 202, 200 -> FeedSignalOutcome.ACCEPTED;
            case 501 -> FeedSignalOutcome.UNSUPPORTED;
            case 409 -> FeedSignalOutcome.REJECTED;
            default -> throw failure(response, url);
        };
    }

    // ── HTTP plumbing ────────────────────────────────────────────────

    private JsonNode getJson(URI url, @Nullable FeedActor actor) {
        CentauriHttpClient.Response response = call(
                () -> http.get(url, headers(actor), TIMEOUT), url);
        requireSuccess(response, url);
        return parse(response.body(), url);
    }

    /**
     * Headers for one call. The credential is read here rather than held: the
     * supplier reaches into the settings cascade on every request, so a rotated
     * key takes effect without rebuilding this instance.
     */
    private Map<String, String> headers(@Nullable FeedActor actor) {
        Map<String, String> headers = new LinkedHashMap<>();
        String credential = cfg.credential();
        if (StringUtils.isNotBlank(credential)) {
            headers.put("Authorization", "Bearer " + credential);
        }
        if (actor != null) {
            headers.put(HEADER_READER, actor.pseudonym());
        }
        return headers;
    }

    private CentauriHttpClient.Response call(Call call, URI url) {
        try {
            return call.execute();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FeedException("interrupted while calling " + url, e);
        } catch (Exception e) {
            throw new FeedException("could not reach " + url + ": " + e, e);
        }
    }

    private void requireSuccess(CentauriHttpClient.Response response, URI url) {
        if (!response.isSuccess()) {
            throw failure(response, url);
        }
    }

    /**
     * The status goes into the message on purpose: the failure tracker extracts
     * it to decide whether this warrants a cooldown, and a 400 from a wrong
     * selector should not be treated like a source that is down.
     */
    private FeedException failure(CentauriHttpClient.Response response, URI url) {
        return new FeedException("HTTP " + response.statusCode() + " from " + url
                + (response.body().isEmpty() ? "" : ": " + abbreviate(response.body())));
    }

    private JsonNode parse(String body, URI url) {
        try {
            return mapper.readTree(body);
        } catch (RuntimeException e) {
            throw new FeedException("unreadable response from " + url + ": " + e, e);
        }
    }

    // ── mapping ──────────────────────────────────────────────────────

    /**
     * One entry, or null when it lacks what the contract requires. Skipping a
     * broken entry is right where failing the page would not be: one malformed
     * row must not cost the reader the other nineteen.
     */
    private @Nullable FeedItem toItem(JsonNode node) {
        String id = text(node, "id");
        String url = text(node, "url");
        Instant publishedAt = instant(text(node, "publishedAt"));
        if (StringUtils.isBlank(id) || StringUtils.isBlank(url) || publishedAt == null) {
            log.warn("Centauri/ode '{}': skipping entry without id, url or publishedAt: {}",
                    cfg.instanceId(), abbreviate(node.toString()));
            return null;
        }
        return new FeedItem(
                id,
                // The source's own resume token for this entry. Without it the
                // merge falls back to the item id, which is wrong for any source
                // paging by (publishedAt, id) — and wrong silently: such a source
                // reads a bare id as "start from the top" and the scroll repeats.
                blankToNull(text(node, "cursor")),
                publishedAt,
                StringUtils.defaultIfBlank(text(node, "title"), url),
                url,
                blankToNull(text(node, "summary")),
                blankToNull(text(node, "body")),
                blankToNull(text(node, "author")),
                blankToNull(text(node, "language")),
                blankToNull(text(node, "imageUrl")),
                controlUrl(text(node, "controlUrl")),
                strings(node.path("tags")),
                extras(node.path("extras")));
    }

    /**
     * The source's own fields, as it wrote them.
     *
     * <p>This used to be {@code Map.of()} — the far end filled it, the wire
     * carried it, and the parser dropped it, which made the whole channel
     * unusable without anything failing. What travels here is per-source and
     * untyped by design (a place name, a word count, which feeds delivered a
     * deduplicated article); it is for display, never for filtering, because
     * a filter over keys nobody declared means something different at every
     * source.
     */
    private Map<String, Object> extras(JsonNode node) {
        if (!node.isObject() || node.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        node.properties().forEach(e -> {
            Object value = mapper.convertValue(e.getValue(), Object.class);
            if (value != null) {
                out.put(e.getKey(), value);
            }
        });
        return Map.copyOf(out);
    }

    /**
     * A remote-supplied link becomes an {@code <a href>} in the reader's UI, so
     * it is checked here rather than there: https only, and the host must match
     * the base URL this source is configured under. Without the host check a
     * compromised source could deep-link anywhere it liked.
     */
    private @Nullable String controlUrl(@Nullable String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            URI candidate = URI.create(raw.trim());
            URI base = URI.create(cfg.baseUrl());
            boolean https = "https".equalsIgnoreCase(candidate.getScheme());
            boolean sameHost = candidate.getHost() != null
                    && candidate.getHost().equalsIgnoreCase(base.getHost());
            if (https && sameHost) {
                return candidate.toString();
            }
            log.debug("Centauri/ode '{}': dropped controlUrl '{}' — needs https and host '{}'",
                    cfg.instanceId(), raw, base.getHost());
            return null;
        } catch (RuntimeException e) {
            log.debug("Centauri/ode '{}': dropped unparseable controlUrl '{}'",
                    cfg.instanceId(), raw);
            return null;
        }
    }

    private static List<String> strings(JsonNode array) {
        if (!array.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(array.size());
        for (JsonNode entry : array) {
            String value = entry.asString("");
            if (!value.isBlank()) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    private static <E extends Enum<E>> Set<E> enumSet(JsonNode array, Class<E> type) {
        if (!array.isArray()) {
            return Set.of();
        }
        Set<E> out = new LinkedHashSet<>();
        for (JsonNode entry : array) {
            E value = enumOf(type, entry.asString(""), null);
            if (value != null) {
                out.add(value);
            }
        }
        return Set.copyOf(out);
    }

    /**
     * Unknown enum values are dropped rather than fatal: a newer source may
     * name a signal or selector kind this build does not have, and refusing the
     * whole response over it would make every capability addition a breaking
     * change.
     */
    private static <E extends Enum<E>> @Nullable E enumOf(
            Class<E> type, @Nullable String raw, @Nullable E fallback) {
        if (StringUtils.isBlank(raw)) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static @Nullable Instant instant(@Nullable String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Duration duration(@Nullable String raw) {
        if (StringUtils.isBlank(raw)) {
            return FeedCapabilities.DEFAULT_TTL;
        }
        try {
            return Duration.parse(raw.trim());
        } catch (RuntimeException e) {
            return FeedCapabilities.DEFAULT_TTL;
        }
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asString("");
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * Percent-encode one path segment.
     *
     * <p>Not {@code URLEncoder}: that is form encoding, where a space becomes
     * {@code +} — which in a path is a literal plus and addresses a different
     * entry. Item ids are usually opaque tokens where the difference never
     * shows, which is exactly why it would be found late.
     */
    private static String urlPathSegment(String raw) {
        return org.springframework.web.util.UriUtils.encodePathSegment(
                raw, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    /** Lets the four call sites share one exception translation. */
    private interface Call {
        CentauriHttpClient.Response execute() throws Exception;
    }
}
