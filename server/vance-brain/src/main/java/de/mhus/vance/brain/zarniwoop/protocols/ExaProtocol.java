package de.mhus.vance.brain.zarniwoop.protocols;

import de.mhus.vance.brain.zarniwoop.protocols.JsonHttpClient.JsonResponse;
import de.mhus.vance.toolpack.research.ContentInline;
import de.mhus.vance.toolpack.research.ContentReference;
import de.mhus.vance.toolpack.research.ProviderAvailability;
import de.mhus.vance.toolpack.research.ProviderInstanceConfig;
import de.mhus.vance.toolpack.research.QuotaStatus;
import de.mhus.vance.toolpack.research.SearchDomain;
import de.mhus.vance.toolpack.research.SearchHit;
import de.mhus.vance.toolpack.research.SearchModality;
import de.mhus.vance.toolpack.research.SearchProtocol;
import de.mhus.vance.toolpack.research.SearchProviderInstance;
import de.mhus.vance.toolpack.research.SearchRequest;
import de.mhus.vance.toolpack.research.SearchResult;
import de.mhus.vance.toolpack.research.SearchScope;
import de.mhus.vance.toolpack.research.SearchTier;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Exa neural web search over the plain REST API
 * ({@code POST https://api.exa.ai/search}, {@code x-api-key}).
 *
 * <p>Wire choice: REST, not the hosted MCP server. The MCP surface is an
 * agent tool surface (its default search tool answers LLM prose, not typed
 * data) while the REST endpoint is the versioned data contract with real
 * error codes — and the free account tier ($10 credits per month, documented
 * 402/429) sits behind the same REST call. See
 * {@code planning/zarniwoop-exa-firecrawl-searxng.md} §2.
 *
 * <p>Quota semantics: <b>402 is a state, not a failure</b> — the team's
 * credits or the key budget are spent, which the provider reports as a
 * terminating condition. That becomes
 * {@link de.mhus.vance.toolpack.research.SearchQuotaExceededException}; the
 * dispatcher cools the instance down until the quota returns and cascades to
 * the next candidate. 429 is temporary pressure and a hard failure whose
 * {@code Retry-After} drives the cooldown.
 */
@Component
@Slf4j
public class ExaProtocol implements SearchProtocol {

    public static final String ID = "exa";
    private static final String USER_AGENT = "Vance-Zarniwoop/0.1 (+https://github.com/mhus/vance)";
    private static final String DEFAULT_BASE_URL = "https://api.exa.ai";

    private final ObjectMapper objectMapper;
    private final JsonHttpClient http;

    @Autowired
    public ExaProtocol(ObjectMapper objectMapper) {
        this(objectMapper, new JsonHttpClient.JdkJsonHttpClient());
    }

    ExaProtocol(ObjectMapper objectMapper, JsonHttpClient http) {
        this.objectMapper = objectMapper;
        this.http = http;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Exa";
    }

    @Override
    public Set<SearchModality> modalitiesSupported() {
        return Set.of(SearchModality.WEB, SearchModality.NEWS, SearchModality.ACADEMIC);
    }

    @Override
    public Set<SearchTier> tiersSupported() {
        return Set.of(SearchTier.NORMAL, SearchTier.EXPERT);
    }

    @Override
    public SearchProviderInstance instantiate(ProviderInstanceConfig cfg) {
        if (cfg == null) throw new IllegalArgumentException("cfg is required");
        if (!ID.equals(cfg.protocolId())) {
            throw new IllegalArgumentException(
                    "ExaProtocol cannot instantiate config with protocol '" + cfg.protocolId() + "'");
        }
        return new ExaInstance(cfg, objectMapper, http);
    }

    // ── Instance ─────────────────────────────────────────────────────

    static final class ExaInstance implements SearchProviderInstance {

        private static final Duration TIMEOUT = Duration.ofSeconds(20);
        private static final int DEFAULT_NUM = 10;
        private static final int MAX_NUM = 100;

        private final ProviderInstanceConfig cfg;
        private final ObjectMapper objectMapper;
        private final JsonHttpClient http;

        ExaInstance(ProviderInstanceConfig cfg, ObjectMapper objectMapper, JsonHttpClient http) {
            this.cfg = cfg;
            this.objectMapper = objectMapper;
            this.http = http;
        }

        @Override
        public String id() {
            return cfg.instanceId();
        }

        @Override
        public String displayName() {
            return "Exa (" + cfg.instanceId() + ")";
        }

        @Override
        public Set<SearchModality> modalities() {
            return Set.of(SearchModality.WEB, SearchModality.NEWS, SearchModality.ACADEMIC);
        }

        @Override
        public Set<SearchDomain> domains() {
            return Set.of(SearchDomain.GENERAL, SearchDomain.NEWS, SearchDomain.ACADEMIC);
        }

        @Override
        public Set<SearchTier> tiers() {
            return Set.of(SearchTier.NORMAL, SearchTier.EXPERT);
        }

        @Override
        public ProviderAvailability availability(SearchScope scope) {
            // The REST API has no keyless mode — an Exa key is free to get
            // ($10 credits per month), but without one there is nothing to
            // ask with.
            return StringUtils.isBlank(cfg.credential())
                    ? ProviderAvailability.NO_CREDENTIALS
                    : ProviderAvailability.READY;
        }

        @Override
        public Optional<QuotaStatus> currentQuota(SearchScope scope) {
            // No cheap "remaining" endpoint for a single key — billing is per
            // team with a monthly grant. The reactive path is the 402, and it
            // is exact.
            return Optional.empty();
        }

        @Override
        public String statusText(SearchScope scope) {
            return StringUtils.isBlank(cfg.credential())
                    ? "no API key (x-api-key) configured"
                    : "x-api-key configured (team credits apply)";
        }

        @Override
        public String promptHint() {
            return "Exa neural search — embeddings-based web search that "
                    + "finds pages by meaning, not just keywords. Best for: "
                    + "people/company pages, niche topics where keyword "
                    + "search misses paraphrases, and current information. "
                    + "Modalities: web (default), news (category `news`), "
                    + "academic (category `publication` — scholarly papers "
                    + "with authors/venue metadata). Expert filters: "
                    + "`type` (auto|fast|instant|deep-lite|deep), `category`, "
                    + "`includeDomains`, `excludeDomains`, `startPublishedDate`, "
                    + "`endPublishedDate` (ISO dates). Each call spends team "
                    + "credits; when they run out the source steps aside and "
                    + "the fallback chain answers.";
        }

        @Override
        public SearchResult search(SearchRequest req, SearchScope scope) {
            String credential = cfg.credential();
            if (StringUtils.isBlank(credential)) {
                return SearchResult.softFailure(
                        req, cfg.instanceId(), "Exa '" + cfg.instanceId() + "' has no API key configured");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", req.query());
            body.put("numResults", clampNum(req.maxResults()));
            String type = expertString(req, "type", cfg.extra("type", "auto"));
            body.put("type", type);
            String category = expertString(req, "category", categoryFor(req.modality()));
            if (!StringUtils.isBlank(category)) {
                body.put("category", category);
            }
            // Highlights are the cheap content channel: passages rather than
            // full page text, and what the body channel surfaces downstream.
            body.put("contents", Map.of("highlights", true));
            applyDomainAndDateFilters(req, body);

            String url = baseUrl() + "/search";
            JsonResponse response;
            try {
                response = http.post(
                        URI.create(url),
                        Map.of("x-api-key", credential, "User-Agent", USER_AGENT),
                        objectMapper.writeValueAsString(body),
                        TIMEOUT);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while calling Exa '" + cfg.instanceId() + "'", ie);
            } catch (Exception e) {
                throw new RuntimeException("Exa '" + cfg.instanceId() + "' call failed: " + e.getMessage(), e);
            }
            if (response.statusCode() != 200) {
                throw ProtocolErrors.httpFailure(
                        objectMapper, "Exa '" + cfg.instanceId() + "' /search", response, null);
            }
            List<SearchHit> hits = parseHits(response.body(), req.modality());
            return new SearchResult(
                    req.query(),
                    req.modality(),
                    cfg.instanceId(),
                    req.tier(),
                    hits,
                    hits.size(),
                    0,
                    null,
                    null,
                    response.headers());
        }

        /** Exa category for a modality — WEB searches without one. */
        private static String categoryFor(SearchModality modality) {
            return switch (modality) {
                case NEWS -> "news";
                case ACADEMIC -> "publication";
                default -> "";
            };
        }

        /**
         * Domain and date filters. The named expert filters of the tool
         * surface ({@code site}, {@code domain}, {@code dateFrom},
         * {@code dateTo}) map onto Exa's fields; anything that already uses
         * Exa's own spelling passes through unchanged.
         */
        private static void applyDomainAndDateFilters(SearchRequest req, Map<String, Object> body) {
            List<String> include = strings(req, "includeDomains", "site", "domain");
            if (!include.isEmpty()) {
                body.put("includeDomains", include);
            }
            List<String> exclude = strings(req, "excludeDomains");
            if (!exclude.isEmpty()) {
                body.put("excludeDomains", exclude);
            }
            String from = expertString(req, "startPublishedDate", expertString(req, "dateFrom", ""));
            if (!from.isEmpty()) {
                body.put("startPublishedDate", from);
            }
            String to = expertString(req, "endPublishedDate", expertString(req, "dateTo", ""));
            if (!to.isEmpty()) {
                body.put("endPublishedDate", to);
            }
        }

        private static List<String> strings(SearchRequest req, String... keys) {
            List<String> out = new ArrayList<>();
            for (String key : keys) {
                Object value = req.expertParams().get(key);
                if (value == null) {
                    continue;
                }
                if (value instanceof Iterable<?> iterable) {
                    for (Object item : iterable) {
                        if (item != null && !String.valueOf(item).isBlank()) {
                            out.add(String.valueOf(item).trim());
                        }
                    }
                } else {
                    String text = String.valueOf(value).trim();
                    if (!text.isEmpty()) {
                        out.add(text);
                    }
                }
            }
            return out;
        }

        private static String expertString(SearchRequest req, String key, String fallback) {
            Object value = req.expertParams().get(key);
            if (value == null) {
                return fallback;
            }
            String text = String.valueOf(value).trim();
            return text.isEmpty() ? fallback : text;
        }

        private String baseUrl() {
            return StringUtils.isBlank(cfg.baseUrl()) ? DEFAULT_BASE_URL : cfg.baseUrl();
        }

        private static int clampNum(int requested) {
            if (requested <= 0) {
                return DEFAULT_NUM;
            }
            return Math.min(requested, MAX_NUM);
        }

        List<SearchHit> parseHits(String json, SearchModality modality) {
            JsonNode root;
            try {
                root = objectMapper.readTree(json);
            } catch (RuntimeException e) {
                log.warn("Exa '{}': response is not JSON: {}", cfg.instanceId(), e.toString());
                return List.of();
            }
            JsonNode arr = root.path("results");
            List<SearchHit> out = new ArrayList<>();
            if (!arr.isArray()) {
                return out;
            }
            for (JsonNode item : arr) {
                SearchHit hit = parseOneHit(item, modality);
                if (hit != null) {
                    out.add(hit);
                }
            }
            return out;
        }

        private SearchHit parseOneHit(JsonNode item, SearchModality modality) {
            String url = item.path("url").asText("");
            if (StringUtils.isBlank(url)) {
                return null;
            }
            String title = item.path("title").asText("");
            if (StringUtils.isBlank(title)) {
                title = url;
            }

            List<String> highlights = new ArrayList<>();
            JsonNode highlightsNode = item.path("highlights");
            if (highlightsNode.isArray()) {
                for (JsonNode h : highlightsNode) {
                    if (!StringUtils.isBlank(h.asText(""))) {
                        highlights.add(h.asText());
                    }
                }
            }
            String text = item.path("text").asText("");
            String summary = item.path("summary").asText("");
            String snippet =
                    !highlights.isEmpty() ? highlights.get(0) : (StringUtils.isBlank(summary) ? null : summary);

            // Body channel: Exa's own text when it came back, else the
            // highlight passages — capped to 1000 chars by SearchHitRows.
            String bodyText = StringUtils.isBlank(text) ? String.join("\n\n", highlights) : text;
            ContentReference content = null;
            if (!StringUtils.isBlank(bodyText)) {
                content = new ContentReference(
                        cfg.instanceId() + ":hit:" + UUID.randomUUID(),
                        "text/plain",
                        bodyText.length(),
                        ContentInline.EMBED_TEXT,
                        bodyText,
                        null);
            }

            Map<String, Object> extras = new LinkedHashMap<>();
            putIfPresent(extras, "publishedDate", item.path("publishedDate").asText(""));
            putIfPresent(extras, "author", item.path("author").asText(""));
            putIfPresent(extras, "imageUrl", item.path("image").asText(""));
            putIfPresent(extras, "favicon", item.path("favicon").asText(""));

            return new SearchHit(title, url, snippet, "Exa", modality, content, extras);
        }

        private static void putIfPresent(Map<String, Object> extras, String key, String value) {
            if (!StringUtils.isBlank(value)) {
                extras.put(key, value);
            }
        }
    }
}
