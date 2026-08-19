package de.mhus.vance.brain.zarniwoop.protocols;

import de.mhus.vance.brain.zarniwoop.ZarniwoopContentStore;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.toolpack.research.ContentInline;
import de.mhus.vance.toolpack.research.ContentReference;
import de.mhus.vance.toolpack.research.LoadedContent;
import de.mhus.vance.toolpack.research.ProviderAvailability;
import de.mhus.vance.toolpack.research.ProviderInstanceConfig;
import de.mhus.vance.toolpack.research.QuotaStatus;
import de.mhus.vance.toolpack.research.SearchDomain;
import de.mhus.vance.toolpack.research.SearchHit;
import de.mhus.vance.toolpack.research.SearchModality;
import de.mhus.vance.toolpack.research.SearchProviderInstance;
import de.mhus.vance.toolpack.research.SearchRequest;
import de.mhus.vance.toolpack.research.SearchResult;
import de.mhus.vance.toolpack.research.SearchScope;
import de.mhus.vance.toolpack.research.SearchTier;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One configured Ode search endpoint.
 *
 * <p><b>What can be searched here is not our decision.</b> The instance fetches
 * {@code GET {baseUrl}/capabilities} and reports the modalities, domains and
 * tiers that service declares; the dispatcher then filters on them like it does
 * for any other provider. No special case anywhere in the dispatch path — which
 * is the whole reason this fits.
 *
 * <p><b>Everything from the far end is parsed defensively.</b> These bytes come
 * from software we do not own, so a field may be missing, a number may be a
 * string and an enum may be a word we have never heard. The rule throughout is
 * the one Centauri settled on: <i>a broken assurance is logged, not rejected</i>
 * — one unusable hit is skipped, the other nineteen still reach the model. The
 * alternative, failing the response, hands a whole research turn to a single
 * malformed row.
 *
 * <p><b>Caching and the way out of it.</b> Capabilities are held for the life of
 * this instance, and the instance lives in {@code SearchProviderFactory}'s
 * project cache — so the existing "Reload" in the insights tab, which evicts
 * that cache, is also the escape hatch here. A second cache with a second
 * refresh button would only give an operator two things to distrust. A long-
 * lived instance still re-reads after {@link #DEFAULT_CAPS_TTL} so a source that
 * gains a modality is picked up without a restart, and
 * {@link #EXTRA_CAPS_TTL_SECONDS} moves that per endpoint.
 */
@Slf4j
final class OdeSearchInstance implements SearchProviderInstance {

    /** Per-request budget. A slow source holds up a turn someone is waiting on. */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /** Capabilities are cheap to re-read and this only bounds a stale declaration. */
    static final Duration DEFAULT_CAPS_TTL = Duration.ofMinutes(30);

    /**
     * Endpoint setting that overrides {@link #DEFAULT_CAPS_TTL}, in seconds —
     * {@code research.endpoint.<id>.capsTtlSeconds}. For a source whose
     * abilities move (a catalogue that gains a collection), and {@code 0} for
     * one being set up, where waiting out a cache is the wrong kind of puzzle.
     */
    static final String EXTRA_CAPS_TTL_SECONDS = "capsTtlSeconds";

    private static final int DEFAULT_MAX_RESULTS = 10;

    private final ProviderInstanceConfig cfg;
    private final SettingService settings;
    private final ObjectMapper objectMapper;
    private final ZarniwoopContentStore contentStore;
    private final OdeSearchProtocol.OdeSearchHttp http;
    private final Duration capsTtl;

    /** Last successfully fetched declaration, or null while none has arrived. */
    private volatile @Nullable Caps caps;
    private volatile Instant capsFetchedAt = Instant.EPOCH;

    /**
     * Why the last capabilities read failed, for the operator-facing status
     * line. An unreachable source has to be <b>visible</b>: a missing row reads
     * as „never configured" and sends the operator to the wrong place.
     */
    private volatile @Nullable String lastError;

    OdeSearchInstance(
            ProviderInstanceConfig cfg,
            SettingService settings,
            ObjectMapper objectMapper,
            ZarniwoopContentStore contentStore,
            OdeSearchProtocol.OdeSearchHttp http) {
        this.cfg = cfg;
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.contentStore = contentStore;
        this.http = http;
        this.capsTtl = readCapsTtl(cfg);
    }

    /**
     * Endpoint settings arrive as strings, and a wrong one must not take the
     * endpoint down — an unreadable value falls back to the default and says so.
     */
    private static Duration readCapsTtl(ProviderInstanceConfig cfg) {
        Object raw = cfg.extras().get(EXTRA_CAPS_TTL_SECONDS);
        if (raw == null) {
            return DEFAULT_CAPS_TTL;
        }
        try {
            long seconds = Long.parseLong(String.valueOf(raw).trim());
            return seconds < 0 ? DEFAULT_CAPS_TTL : Duration.ofSeconds(seconds);
        } catch (NumberFormatException e) {
            log.warn("Ode endpoint '{}': {}='{}' is not a number, using {}",
                    cfg.instanceId(), EXTRA_CAPS_TTL_SECONDS, raw, DEFAULT_CAPS_TTL);
            return DEFAULT_CAPS_TTL;
        }
    }

    @Override
    public String id() {
        return cfg.instanceId();
    }

    @Override
    public String displayName() {
        return "Ode search (" + cfg.instanceId() + ")";
    }

    @Override
    public Set<SearchModality> modalities() {
        Caps c = capsOrNull();
        return c == null ? Set.of() : c.modalities();
    }

    @Override
    public Set<SearchDomain> domains() {
        Caps c = capsOrNull();
        return c == null ? Set.of(SearchDomain.GENERAL) : c.domains();
    }

    @Override
    public Set<SearchTier> tiers() {
        Caps c = capsOrNull();
        return c == null ? Set.of(SearchTier.NORMAL) : c.tiers();
    }

    /**
     * Optimistically {@link ProviderAvailability#READY}.
     *
     * <p>A health call per search would double the requests for a signal the
     * search itself already gives, and the states that matter are decided
     * elsewhere anyway: {@code COOLDOWN} comes from the failure tracker,
     * {@code DISABLED} from the gate. What is left is genuinely local — a base
     * URL we do not have.
     *
     * <p>Note what this deliberately does <b>not</b> report: an unreachable
     * source. The enum has no word for it, and borrowing {@code DISABLED} would
     * claim an operator switched it off. It surfaces through
     * {@link #statusText(SearchScope)} instead, where it reads as what it is.
     */
    @Override
    public ProviderAvailability availability(SearchScope scope) {
        return StringUtils.isBlank(cfg.baseUrl())
                ? ProviderAvailability.DISABLED
                : ProviderAvailability.READY;
    }

    @Override
    public Optional<QuotaStatus> currentQuota(SearchScope scope) {
        // A foreign source may well have an upstream limit, but until one
        // actually does and the dispatcher needs to know, asking for a number
        // nobody reads is a request per search for nothing.
        return Optional.empty();
    }

    /**
     * A factual line derived from the source's own declaration — not text the
     * source wrote.
     *
     * <p>The distinction is the point. Zarniwoop shows this to the model, and a
     * remote-authored description would be foreign prose inside a system prompt:
     * a separate decision with its own justification, which is why the Ode
     * contract has no field for it. Naming the declared modalities is safe
     * because we produced the sentence.
     */
    @Override
    public String promptHint() {
        Caps c = capsOrNull();
        if (c == null) {
            return "Foreign search endpoint '" + cfg.instanceId()
                    + "' (Ode). Its capabilities could not be read, so it is "
                    + "currently unusable.";
        }
        StringBuilder sb = new StringBuilder()
                .append("Foreign search endpoint '").append(cfg.instanceId())
                .append("' (Ode), serving ").append(names(c.modalities()))
                .append(". Subject areas: ").append(names(c.domains())).append('.');
        if (!c.expertParams().isEmpty()) {
            sb.append(" Expert filters it understands: ")
                    .append(String.join(", ", c.expertParams())).append('.');
        }
        sb.append(" What it indexes is defined by the operating application, "
                + "not by Vancetope.");
        return sb.toString();
    }

    @Override
    public @Nullable String statusText(SearchScope scope) {
        return lastError;
    }

    @Override
    public SearchResult search(SearchRequest req, SearchScope scope) {
        Caps c = capsOrNull();
        if (c == null) {
            // Not a throw: the dispatcher would set a cooldown on a source we
            // may simply not have reached yet, and this path is also hit by a
            // brand-new endpoint on its first call.
            return softFailure(req, "Ode endpoint '" + cfg.instanceId()
                    + "' capabilities unavailable"
                    + (lastError == null ? "" : ": " + lastError));
        }
        if (!c.modalities().contains(req.modality())) {
            return softFailure(req, "modality " + req.modality()
                    + " not served by Ode endpoint '" + cfg.instanceId() + "'");
        }

        SearchTier tier = c.tiers().contains(req.tier()) ? req.tier() : SearchTier.NORMAL;
        int maxResults = clampMaxResults(req.maxResults(), c.maxResults());
        String body = requestJson(req, tier, maxResults);

        OdeSearchProtocol.OdeSearchHttp.Response response;
        try {
            response = http.post(
                    URI.create(baseUrl() + "/search"), apiKey(scope), body, REQUEST_TIMEOUT);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(
                    "Interrupted while calling Ode endpoint '" + cfg.instanceId() + "'");
        } catch (Exception e) {
            throw new RuntimeException("Ode endpoint '" + cfg.instanceId()
                    + "' call failed: " + e.getMessage(), e);
        }
        if (response.statusCode() != 200) {
            // The status goes in the message on purpose: the failure tracker
            // classifies from the text, and 401 (fix the key) must not be
            // treated like 503 (wait).
            throw new RuntimeException("Ode endpoint '" + cfg.instanceId()
                    + "' returned HTTP " + response.statusCode()
                    + shortDetail(response.body()));
        }

        return parseResult(response.body(), req, tier);
    }

    /**
     * Fetch a body that was returned as
     * {@link ContentInline#STASH_ON_DEMAND}.
     *
     * <p>Implemented although <b>nothing in the brain calls it yet</b> — the SPI
     * method has no consumer today, so an Ode source that stashes rather than
     * embeds sees its bodies go unread. That is a gap on our side, not in the
     * contract, and this is the half that will not need writing when it closes.
     * Sources with short bodies should embed them and be done.
     */
    @Override
    public LoadedContent loadContent(ContentReference ref, SearchScope scope) {
        if (ref == null || StringUtils.isBlank(ref.contentId())) {
            throw new IllegalArgumentException("contentId is required");
        }
        String remoteId = remoteContentId(ref.contentId());
        URI uri = URI.create(baseUrl() + "/content/"
                + URLEncoder.encode(remoteId, StandardCharsets.UTF_8));
        OdeSearchProtocol.OdeSearchHttp.BinaryResponse response;
        try {
            response = http.getBytes(uri, apiKey(scope), REQUEST_TIMEOUT);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(
                    "Interrupted while loading content from '" + cfg.instanceId() + "'");
        } catch (Exception e) {
            throw new RuntimeException("Ode endpoint '" + cfg.instanceId()
                    + "' content fetch failed: " + e.getMessage(), e);
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException("Ode endpoint '" + cfg.instanceId()
                    + "' content fetch returned HTTP " + response.statusCode());
        }
        String mime = StringUtils.isBlank(response.contentType())
                ? StringUtils.defaultIfBlank(ref.mimeType(), "application/octet-stream")
                : stripCharset(response.contentType());
        Path stashed = contentStore.stash(scope, remoteId, response.body(), mime);
        String text = mime.startsWith("text/")
                ? new String(response.body(), StandardCharsets.UTF_8)
                : null;
        return new LoadedContent(mime, stashed, text);
    }

    // ── capabilities ─────────────────────────────────────────────────

    /**
     * The cached declaration, re-reading when absent or stale. Returns null when
     * the source cannot be reached — callers treat that as „nothing to serve"
     * rather than as an error, so a dead endpoint costs a skipped provider and
     * not a failed turn.
     */
    private @Nullable Caps capsOrNull() {
        Caps current = caps;
        if (current != null && !capsTtl.isZero()
                && Duration.between(capsFetchedAt, Instant.now()).compareTo(capsTtl) < 0) {
            return current;
        }
        try {
            OdeSearchProtocol.OdeSearchHttp.Response response = http.get(
                    URI.create(baseUrl() + "/capabilities"), apiKeyForCapabilities(),
                    REQUEST_TIMEOUT);
            if (response.statusCode() != 200) {
                return failedCaps(current, "capabilities returned HTTP "
                        + response.statusCode());
            }
            Caps parsed = parseCaps(response.body());
            caps = parsed;
            capsFetchedAt = Instant.now();
            lastError = null;
            return parsed;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return failedCaps(current, "interrupted while reading capabilities");
        } catch (Exception e) {
            return failedCaps(current, "capabilities unreadable: " + e.getMessage());
        }
    }

    /**
     * Keep serving a stale declaration when a refresh fails. A source that was
     * answering a minute ago is more likely briefly unreachable than genuinely
     * empty, and dropping to „serves nothing" would take it out of dispatch for
     * a blip.
     */
    private @Nullable Caps failedCaps(@Nullable Caps previous, String message) {
        lastError = message;
        log.warn("Ode endpoint '{}': {}", cfg.instanceId(), message);
        return previous;
    }

    Caps parseCaps(String json) {
        JsonNode root = objectMapper.readTree(json);
        Set<SearchModality> modalities = parseEnums(
                root.path("modalities"), SearchModality.class, "modality");
        Set<SearchDomain> domains = parseEnums(
                root.path("domains"), SearchDomain.class, "domain");
        Set<SearchTier> tiers = parseEnums(root.path("tiers"), SearchTier.class, "tier");
        if (tiers.isEmpty()) {
            tiers = Set.of(SearchTier.NORMAL);
        }
        if (domains.isEmpty()) {
            domains = Set.of(SearchDomain.GENERAL);
        }
        int maxResults = root.path("maxResults").asInt(DEFAULT_MAX_RESULTS);
        List<String> expertParams = new ArrayList<>();
        JsonNode params = root.path("expertParams");
        if (params.isArray()) {
            for (JsonNode p : params) {
                String name = p.asString("");
                if (!StringUtils.isBlank(name)) {
                    expertParams.add(name);
                }
            }
        }
        return new Caps(
                modalities, domains, tiers,
                maxResults <= 0 ? DEFAULT_MAX_RESULTS : maxResults,
                List.copyOf(expertParams),
                root.path("servesContent").asBoolean(false));
    }

    /**
     * Read a set of enum values, skipping words this version does not know.
     *
     * <p>Both vocabularies are closed and mirrored on the Ode side, so an unknown
     * value means the two ends are on different versions. Skipping one modality
     * and logging is recoverable; refusing the whole declaration would take a
     * working source offline over a value we did not need.
     */
    private <E extends Enum<E>> Set<E> parseEnums(JsonNode array, Class<E> type, String what) {
        if (!array.isArray()) {
            return Set.of();
        }
        EnumSet<E> out = EnumSet.noneOf(type);
        for (JsonNode node : array) {
            String raw = node.asString("");
            if (StringUtils.isBlank(raw)) {
                continue;
            }
            try {
                out.add(Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                log.warn("Ode endpoint '{}' declares unknown {} '{}' — ignored. "
                                + "The vocabulary is closed; this end may be older.",
                        cfg.instanceId(), what, raw);
            }
        }
        return Set.copyOf(out);
    }

    // ── search wire ──────────────────────────────────────────────────

    private String requestJson(SearchRequest req, SearchTier tier, int maxResults) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", req.query());
        body.put("modality", req.modality().name());
        body.put("tier", tier.name());
        body.put("maxResults", maxResults);
        if (req.locale() != null) {
            body.put("locale", req.locale().toLanguageTag());
        }
        // Passed through as given. Which keys mean something is the source's
        // business, and it is asked to ignore rather than refuse the rest —
        // we cannot know its schema.
        if (tier == SearchTier.EXPERT && !req.expertParams().isEmpty()) {
            body.put("expertParams", req.expertParams());
        }
        return objectMapper.writeValueAsString(body);
    }

    SearchResult parseResult(String json, SearchRequest req, SearchTier tier) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (RuntimeException e) {
            // Unparseable body is a protocol break, and a throw here is right:
            // unlike a missing field this is not something we can work around,
            // and the failure tracker should see it.
            throw new RuntimeException("Ode endpoint '" + cfg.instanceId()
                    + "' returned unparseable JSON: " + e.getMessage(), e);
        }
        List<SearchHit> hits = new ArrayList<>();
        int skipped = 0;
        JsonNode array = root.path("hits");
        if (array.isArray()) {
            for (JsonNode node : array) {
                SearchHit hit = parseHit(node, req.modality());
                if (hit == null) {
                    skipped++;
                    continue;
                }
                hits.add(hit);
            }
        }
        if (skipped > 0) {
            log.warn("Ode endpoint '{}': skipped {} unusable hit(s) — a hit needs "
                    + "a title and a url to be shown at all", cfg.instanceId(), skipped);
        }
        // Truncate rather than complain: the source was told the limit and the
        // caller cannot use more than it asked for.
        if (hits.size() > req.maxResults()) {
            log.warn("Ode endpoint '{}' returned {} hits for maxResults={} — truncating",
                    cfg.instanceId(), hits.size(), req.maxResults());
            hits = hits.subList(0, req.maxResults());
        }
        String note = root.path("note").asString(null);
        int dropped = root.path("droppedCount").asInt(0) + skipped;
        return new SearchResult(
                req.query(), req.modality(), cfg.instanceId(), tier,
                List.copyOf(hits), hits.size(), dropped,
                StringUtils.isBlank(note) ? null : note,
                null, Map.of());
    }

    private @Nullable SearchHit parseHit(JsonNode node, SearchModality fallbackModality) {
        String title = node.path("title").asString("");
        String url = node.path("url").asString("");
        if (StringUtils.isBlank(title) || StringUtils.isBlank(url)) {
            return null;
        }
        SearchModality modality = fallbackModality;
        String rawModality = node.path("modality").asString("");
        if (!StringUtils.isBlank(rawModality)) {
            try {
                modality = SearchModality.valueOf(rawModality.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                log.debug("Ode endpoint '{}': hit declares unknown modality '{}', "
                        + "using the queried one", cfg.instanceId(), rawModality);
            }
        }
        String snippet = node.path("snippet").asString(null);
        String source = node.path("source").asString(null);
        Map<String, Object> extras = new LinkedHashMap<>();
        JsonNode extrasNode = node.path("extras");
        if (extrasNode.isObject()) {
            for (Map.Entry<String, JsonNode> e : extrasNode.properties()) {
                extras.put(e.getKey(), scalar(e.getValue()));
            }
        }
        return new SearchHit(
                title, url,
                StringUtils.isBlank(snippet) ? null : snippet,
                StringUtils.isBlank(source) ? null : source,
                modality,
                parseContent(node.path("content")),
                extras);
    }

    private @Nullable ContentReference parseContent(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        String contentId = node.path("contentId").asString("");
        if (StringUtils.isBlank(contentId)) {
            return null;
        }
        boolean embedded = !"STASH_ON_DEMAND".equalsIgnoreCase(
                node.path("inline").asString("EMBED_TEXT"));
        String text = node.path("text").asString(null);
        if (embedded && StringUtils.isBlank(text)) {
            // An embedded body with no text is an empty promise; dropping the
            // reference is better than handing the model a body it cannot read.
            return null;
        }
        String mime = StringUtils.defaultIfBlank(
                node.path("mimeType").asString(""), "text/plain");
        long size = node.path("sizeBytes").asLong(text == null ? 0L : text.length());
        return new ContentReference(
                // Prefixed with the endpoint so two sources cannot collide on a
                // content id, and stripped again before the id goes back out.
                cfg.instanceId() + ":" + contentId,
                mime, size,
                embedded ? ContentInline.EMBED_TEXT : ContentInline.STASH_ON_DEMAND,
                embedded ? text : null,
                null);
    }

    // ── helpers ──────────────────────────────────────────────────────

    private String baseUrl() {
        String base = cfg.baseUrl();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /**
     * Credential in the Zarniwoop house style: read per call from the scope
     * cascade, so a rotated key takes effect without rebuilding the cache.
     *
     * <p>Blank is legitimate — an Ode endpoint may be open, or guarded by
     * something in front of it. The bearer header is simply omitted.
     */
    private @Nullable String apiKey(SearchScope scope) {
        String key = settings.getDecryptedPasswordCascade(
                scope.tenantId(), scope.projectId(), scope.processId(),
                cfg.credentialSettingKey());
        return StringUtils.isBlank(key) ? null : key;
    }

    /**
     * Credential for the capabilities read, which happens outside any one
     * request and therefore has no {@link SearchScope} to cascade from.
     *
     * <p>It uses the tenant and project this instance was assembled for, at
     * project scope. Not a workaround: what this source can serve is a property
     * of the project's configuration, so resolving it per calling process would
     * be wrong even if a process id were available — the answer is cached and
     * shared by every caller in the project.
     */
    private @Nullable String apiKeyForCapabilities() {
        if (cfg.tenantId() == null || cfg.projectId() == null) {
            // Only the scope-less config form, which the factory never builds.
            return null;
        }
        String key = settings.getDecryptedPasswordCascade(
                cfg.tenantId(), cfg.projectId(), null, cfg.credentialSettingKey());
        return StringUtils.isBlank(key) ? null : key;
    }

    private int clampMaxResults(int requested, int declared) {
        int asked = requested <= 0 ? DEFAULT_MAX_RESULTS : requested;
        return Math.min(asked, declared);
    }

    /** Undo the endpoint prefix {@link #parseContent} adds. */
    private String remoteContentId(String prefixed) {
        String prefix = cfg.instanceId() + ":";
        return prefixed.startsWith(prefix) ? prefixed.substring(prefix.length()) : prefixed;
    }

    private static String stripCharset(String contentType) {
        int semi = contentType.indexOf(';');
        return semi < 0 ? contentType.trim() : contentType.substring(0, semi).trim();
    }

    /**
     * Enough of an error body to be useful in a log line, and no more — a
     * foreign service may answer a failure with a page.
     */
    private static String shortDetail(@Nullable String body) {
        if (StringUtils.isBlank(body)) {
            return "";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return ": " + (flat.length() > 200 ? flat.substring(0, 200) + "…" : flat);
    }

    private static Object scalar(JsonNode node) {
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asString("");
    }

    private static String names(Set<? extends Enum<?>> values) {
        if (values.isEmpty()) {
            return "nothing";
        }
        List<String> sorted = new ArrayList<>();
        for (Enum<?> v : values) {
            sorted.add(v.name());
        }
        sorted.sort(String::compareTo);
        return String.join(", ", sorted);
    }

    private SearchResult softFailure(SearchRequest req, String message) {
        log.debug("Ode endpoint '{}': {}", cfg.instanceId(), message);
        return new SearchResult(
                req.query(), req.modality(), cfg.instanceId(), req.tier(),
                List.of(), 0, 0, null, message, Map.of());
    }

    /** The far end's declaration, once parsed. */
    record Caps(
            Set<SearchModality> modalities,
            Set<SearchDomain> domains,
            Set<SearchTier> tiers,
            int maxResults,
            List<String> expertParams,
            boolean servesContent) { }
}
