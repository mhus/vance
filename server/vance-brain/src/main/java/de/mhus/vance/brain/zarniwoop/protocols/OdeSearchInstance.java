package de.mhus.vance.brain.zarniwoop.protocols;

import de.mhus.vance.brain.prompt.ForeignPromptText;
import de.mhus.vance.brain.zarniwoop.ZarniwoopContentStore;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.SecretResolver;
import de.mhus.vance.toolpack.facet.Facet;
import de.mhus.vance.toolpack.facet.FacetValue;
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
import org.springframework.web.util.UriUtils;
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
 * lived instance still re-reads once its hold time is up, so a source that gains
 * a modality is picked up without a restart. How long that is comes from the
 * source's own {@code cacheTtl}, falling back to {@link #DEFAULT_CAPS_TTL}, with
 * {@link #EXTRA_CAPS_TTL_SECONDS} overriding both for the operator who is
 * debugging.
 *
 * <p><b>A failed read is remembered as failed</b> for {@link #FAILED_CAPS_TTL}.
 * Several methods here consult the declaration and each is called during
 * provider selection, so without that an unreachable endpoint paid the request
 * timeout three or four times per search — and never entered cooldown, because a
 * source reporting „serves nothing" is skipped rather than reported as broken.
 */
@Slf4j
final class OdeSearchInstance implements SearchProviderInstance {

    /** Per-request budget. A slow source holds up a turn someone is waiting on. */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /**
     * Fallback hold time when the source states none. The source's own
     * {@code cacheTtl} wins — it is part of the contract and says how long it
     * wants to be believed; a catalogue that moves hourly should not be pinned
     * to our half hour.
     */
    static final Duration DEFAULT_CAPS_TTL = Duration.ofMinutes(30);

    /**
     * How long a failed capabilities read is remembered as failed.
     *
     * <p>Without this the instance retries on every call, and the calls are not
     * one per search: the dispatcher asks {@code modalities()},
     * {@code domains()} and {@code tiers()} while selecting providers, and the
     * insights view asks again per row. Against an unreachable endpoint each of
     * those pays {@link #REQUEST_TIMEOUT}, so a single dead source used to add
     * three quarters of a minute to every search — and nothing ever put it in
     * cooldown, because a source that reports „serves nothing" is skipped
     * rather than reported as failing.
     *
     * <p>Short on purpose: this is a backoff, not a cache. An endpoint that
     * comes back is usable again within the minute.
     */
    static final Duration FAILED_CAPS_TTL = Duration.ofSeconds(30);

    /**
     * Endpoint setting that overrides both the source's declared TTL and
     * {@link #DEFAULT_CAPS_TTL}, in seconds —
     * {@code research.endpoint.<id>.capsTtlSeconds}. The operator's figure wins
     * over the source's: it is the one who is debugging. {@code 0} means „never
     * hold", for a source being set up, where waiting out a cache is the wrong
     * kind of puzzle.
     */
    static final String EXTRA_CAPS_TTL_SECONDS = "capsTtlSeconds";

    private static final int DEFAULT_MAX_RESULTS = 10;

    /**
     * How many declared expert parameters {@link #promptHint()} will name.
     * Enough to tell the model what kind of endpoint this is; past that the
     * names only cost tokens in a prompt that is rebuilt for every plan.
     */
    static final int MAX_HINTED_EXPERT_PARAMS = 15;

    private final ProviderInstanceConfig cfg;
    private final SettingService settings;
    private final SecretResolver secretResolver;
    private final ObjectMapper objectMapper;
    private final ZarniwoopContentStore contentStore;
    private final OdeSearchProtocol.OdeSearchHttp http;

    /**
     * Operator override, or null to follow whatever the source declares.
     * {@link Duration#ZERO} means „never hold".
     */
    private final @Nullable Duration capsTtlOverride;

    /** Last successfully fetched declaration, or null while none has arrived. */
    private volatile @Nullable Caps caps;
    private volatile Instant capsFetchedAt = Instant.EPOCH;

    /**
     * When the last <em>attempt</em> failed, so a dead endpoint is asked again at
     * most once per {@link #FAILED_CAPS_TTL} instead of once per method call.
     * Separate from {@link #capsFetchedAt}, which must keep meaning „when the
     * declaration we are serving was fetched".
     */
    private volatile Instant capsFailedAt = Instant.EPOCH;

    /**
     * Why the last capabilities read failed, for the operator-facing status
     * line. An unreachable source has to be <b>visible</b>: a missing row reads
     * as „never configured" and sends the operator to the wrong place.
     */
    private volatile @Nullable String lastError;

    OdeSearchInstance(
            ProviderInstanceConfig cfg,
            SettingService settings,
            SecretResolver secretResolver,
            ObjectMapper objectMapper,
            ZarniwoopContentStore contentStore,
            OdeSearchProtocol.OdeSearchHttp http) {
        this.cfg = cfg;
        this.settings = settings;
        this.secretResolver = secretResolver;
        this.objectMapper = objectMapper;
        this.contentStore = contentStore;
        this.http = http;
        this.capsTtlOverride = readCapsTtlOverride(cfg);
    }

    /**
     * Endpoint settings arrive as strings, and a wrong one must not take the
     * endpoint down — an unreadable value is ignored and said so, which leaves
     * the source's own declaration in charge.
     */
    private static @Nullable Duration readCapsTtlOverride(ProviderInstanceConfig cfg) {
        Object raw = cfg.extras().get(EXTRA_CAPS_TTL_SECONDS);
        if (raw == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(String.valueOf(raw).trim());
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException e) {
            log.warn("Ode endpoint '{}': {}='{}' is not a number, ignoring it and following "
                            + "the source's declared cacheTtl",
                    cfg.instanceId(), EXTRA_CAPS_TTL_SECONDS, raw);
            return null;
        }
    }

    /**
     * How long the declaration we hold may be held: the operator's override, else
     * the source's own {@code cacheTtl}, else our default.
     */
    private Duration capsTtl(@Nullable Caps current) {
        if (capsTtlOverride != null) {
            return capsTtlOverride;
        }
        return current == null || current.cacheTtl() == null
                ? DEFAULT_CAPS_TTL
                : current.cacheTtl();
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
    public List<Facet> facets() {
        Caps c = capsOrNull();
        return c == null ? List.of() : c.facets();
    }

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
        // The declared parameter names are the one part of this sentence we do
        // not author, and it is rendered into the plan recipe's system prompt
        // for every research_investigate in the project — then cached for half
        // an hour. Names are filtered against a name grammar rather than
        // quoted, and their number is capped: a remote value that is not a
        // name is prose, and prose from the far end is exactly what the Ode
        // contract has no field for.
        List<String> declared = ForeignPromptText.identifiers(
                c.expertParams(), MAX_HINTED_EXPERT_PARAMS);
        if (!declared.isEmpty()) {
            sb.append(" Expert filters it understands: ")
                    .append(String.join(", ", declared));
            int more = c.expertParams().size() - declared.size();
            if (more > 0) {
                sb.append(" (and ").append(more).append(" more)");
            }
            sb.append('.');
        } else if (!c.expertParams().isEmpty()) {
            sb.append(" It declares ").append(c.expertParams().size())
                    .append(" expert filter(s), none of them a usable name.");
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
        // The number actually asked for is what the answer is held to, below.

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

        return parseResult(response.body(), req, tier, maxResults);
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
        // Path-segment encoding, not URLEncoder: that is form encoding, where a
        // space becomes '+' — a literal plus in a path, addressing something
        // else. Content ids are usually opaque tokens, which is why the
        // difference would be found late.
        URI uri = URI.create(baseUrl() + "/content/"
                + UriUtils.encodePathSegment(remoteId, StandardCharsets.UTF_8));
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
        Duration ttl = capsTtl(current);
        Instant now = Instant.now();
        if (current != null && !ttl.isZero()
                && Duration.between(capsFetchedAt, now).compareTo(ttl) < 0) {
            return current;
        }
        // Back off after a failure instead of re-dialling per method call. Not
        // applied when a declaration is still being served — there the TTL above
        // already bounds the retries, and holding a stale answer back would take
        // a working source out of dispatch.
        if (current == null && !FAILED_CAPS_TTL.isZero()
                && Duration.between(capsFailedAt, now).compareTo(FAILED_CAPS_TTL) < 0) {
            return null;
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
            // A declaration that names no modality this version knows is served
            // — the source answered, and domains/tiers fall back to something
            // usable — but it must not read as healthy. Without a status line
            // the provider panel says READY, no tab appears anywhere, and the
            // operator has nothing to go on. The one thing worse than a missing
            // row is a row that says the wrong thing.
            lastError = parsed.modalities().isEmpty()
                    ? "endpoint declares no modality this version understands"
                    : null;
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
        // Stamped whether or not a stale declaration survives: it is what stops
        // the next of several calls in one dispatch from paying the timeout again.
        capsFailedAt = Instant.now();
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
                root.path("servesContent").asBoolean(false),
                // The contract's own field, and it had no reader: a source that
                // said PT1M was still held for half an hour. Unparseable or
                // non-positive is treated as "not stated" rather than as an
                // error — a bad duration must not take the declaration down.
                duration(root.path("cacheTtl").asString(null)),
                facets(root.path("facets")));
    }

    /**
     * The declared facets. A malformed entry is skipped rather than failing
     * the whole declaration — losing one filter is recoverable, losing the
     * endpoint is not.
     */
    private static List<Facet> facets(JsonNode array) {
        if (!array.isArray()) {
            return List.of();
        }
        List<Facet> out = new ArrayList<>(array.size());
        for (JsonNode entry : array) {
            String key = entry.path("key").asString("");
            if (StringUtils.isBlank(key)) {
                continue;
            }
            try {
                out.add(new Facet(
                        key,
                        StringUtils.defaultIfBlank(entry.path("label").asString(""), key),
                        entry.path("hierarchical").asBoolean(false),
                        facetValues(entry.path("values")),
                        entry.path("lazyChildren").asBoolean(false)));
            } catch (IllegalArgumentException e) {
                log.warn("An Ode endpoint declares unusable facet '{}': {}",
                        key, e.getMessage());
            }
        }
        return List.copyOf(out);
    }

    private static List<FacetValue> facetValues(JsonNode array) {
        if (!array.isArray()) {
            return List.of();
        }
        List<FacetValue> out = new ArrayList<>(array.size());
        for (JsonNode entry : array) {
            String id = entry.path("id").asString("");
            if (StringUtils.isBlank(id)) {
                continue;
            }
            String parent = entry.path("parentId").asString("");
            out.add(new FacetValue(
                    id,
                    StringUtils.defaultIfBlank(entry.path("label").asString(""), id),
                    StringUtils.isBlank(parent) ? null : parent));
        }
        return List.copyOf(out);
    }

    /** ISO-8601 duration, or null when absent or unusable. */
    private @Nullable Duration duration(@Nullable String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            Duration parsed = Duration.parse(raw.trim());
            if (parsed.isNegative() || parsed.isZero()) {
                return null;
            }
            return parsed;
        } catch (RuntimeException e) {
            log.warn("Ode endpoint '{}': cacheTtl '{}' is not an ISO-8601 duration — using {}",
                    cfg.instanceId(), raw, DEFAULT_CAPS_TTL);
            return null;
        }
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
        // Narrowed to what this endpoint declared. Unlike expertParams above,
        // an undeclared key here is not sent and ignored — the dispatcher has
        // already decided not to use an endpoint that cannot answer it, so
        // whatever reaches this point is answerable.
        if (!req.facets().isEmpty()) {
            body.put("facets", req.facets());
        }
        return objectMapper.writeValueAsString(body);
    }

    SearchResult parseResult(
            String json, SearchRequest req, SearchTier tier, int maxResults) {
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
        // caller cannot use more than it asked for. Measured against the number
        // actually sent, not against the request — those differ whenever the
        // source declared a smaller ceiling, and holding it to a limit it was
        // never given would log a broken promise it did not make.
        int truncated = 0;
        if (hits.size() > maxResults) {
            log.warn("Ode endpoint '{}' returned {} hits for maxResults={} — truncating",
                    cfg.instanceId(), hits.size(), maxResults);
            truncated = hits.size() - maxResults;
            hits = hits.subList(0, maxResults);
        }
        String note = root.path("note").asString(null);
        // Truncated hits count as dropped too. Reporting returnedCount=10,
        // droppedCount=0 for a source that sent 40 puts the warning in the log
        // and nothing in the answer — the DTO is what a caller can see.
        int dropped = root.path("droppedCount").asInt(0) + skipped + truncated;
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
        return blankToNull(resolveReference(key, scope.tenantId(), scope.projectId()));
    }

    /**
     * Substitute a {@code {{secret:…}}} reference the setting may hold.
     *
     * <p>Through {@code resolveForConnector} rather than {@code resolve}: a
     * search endpoint is a connector, not a dynamic element, so it may read a
     * {@code PASSWORD}-typed setting or a vault entry (settings spec §10). The
     * restrictive path sees only {@code HIDDEN} and would substitute an empty
     * string — a silent 401 at the far end with nothing anywhere to explain it.
     * Reading the setting straight, as this did, sent the reference itself to
     * the wire verbatim, which fails the same way.
     *
     * <p>The invocation context deliberately carries no user and no process:
     * the endpoint credential is a property of the project's configuration, and
     * this instance is cached per {@code (tenant, project)} and shared by every
     * reader, so a user-scoped reference would hand the first caller's secret
     * to everyone behind them.
     */
    private @Nullable String resolveReference(
            @Nullable String raw, String tenantId, @Nullable String projectId) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        return secretResolver.resolveForConnector(raw, new ToolInvocationContext(
                tenantId, projectId, null, null, null));
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return StringUtils.isBlank(value) ? null : value;
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
        return blankToNull(resolveReference(key, cfg.tenantId(), cfg.projectId()));
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

    /**
     * The far end's declaration, once parsed.
     *
     * @param cacheTtl how long the source asks to be believed, or null when it
     *                 did not say. Honoured unless the operator set
     *                 {@link #EXTRA_CAPS_TTL_SECONDS}.
     */
    record Caps(
            Set<SearchModality> modalities,
            Set<SearchDomain> domains,
            Set<SearchTier> tiers,
            int maxResults,
            List<String> expertParams,
            boolean servesContent,
            @Nullable Duration cacheTtl,
            List<Facet> facets) { }
}
