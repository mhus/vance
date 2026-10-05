package de.mhus.vance.brain.zarniwoop.protocols;

import de.mhus.vance.brain.zarniwoop.protocols.JsonHttpClient.JsonResponse;
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
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * SearXNG metasearch — a self-hosted instance that aggregates the public
 * engines (Google, Bing, DuckDuckGo, …) and answers one JSON API:
 * {@code GET {baseUrl}/search?q=…&format=json&categories=…}.
 *
 * <p>Key-free and self-hosted, which makes it the natural bottom of the
 * fallback chain: unlimited, no credits, no contract — but quality follows
 * whatever engines the instance has enabled. Modality maps onto SearXNG's
 * category axis ({@code general/news/images/videos/files/science/it}); an
 * instance that has a category disabled simply returns no results.
 *
 * <p><b>{@code format=json} must be enabled on the instance</b> ({@code formats:
 * [html, json]} in its {@code settings.yml}). An instance that answers HTML
 * instead is a configuration state, not an outage — the call comes back as a
 * soft failure with that sentence so the operator sees what to fix, and no
 * cooldown is set.
 */
@Component
@Slf4j
public class SearXNGProtocol implements SearchProtocol {

    public static final String ID = "searxng";
    private static final String USER_AGENT = "Vance-Zarniwoop/0.1 (+https://github.com/mhus/vance)";

    private final ObjectMapper objectMapper;
    private final JsonHttpClient http;

    @Autowired
    public SearXNGProtocol(ObjectMapper objectMapper) {
        this(objectMapper, new JsonHttpClient.JdkJsonHttpClient());
    }

    SearXNGProtocol(ObjectMapper objectMapper, JsonHttpClient http) {
        this.objectMapper = objectMapper;
        this.http = http;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "SearXNG";
    }

    @Override
    public Set<SearchModality> modalitiesSupported() {
        return Set.of(
                SearchModality.WEB,
                SearchModality.NEWS,
                SearchModality.IMAGE,
                SearchModality.VIDEO,
                SearchModality.PDF,
                SearchModality.ACADEMIC,
                SearchModality.CODE);
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
                    "SearXNGProtocol cannot instantiate config with protocol '" + cfg.protocolId() + "'");
        }
        return new SearXNGInstance(cfg, objectMapper, http);
    }

    // ── Instance ─────────────────────────────────────────────────────

    static final class SearXNGInstance implements SearchProviderInstance {

        private static final Duration TIMEOUT = Duration.ofSeconds(15);

        private final ProviderInstanceConfig cfg;
        private final ObjectMapper objectMapper;
        private final JsonHttpClient http;

        SearXNGInstance(ProviderInstanceConfig cfg, ObjectMapper objectMapper, JsonHttpClient http) {
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
            return "SearXNG (" + cfg.instanceId() + ")";
        }

        @Override
        public Set<SearchModality> modalities() {
            return Set.of(
                    SearchModality.WEB,
                    SearchModality.NEWS,
                    SearchModality.IMAGE,
                    SearchModality.VIDEO,
                    SearchModality.PDF,
                    SearchModality.ACADEMIC,
                    SearchModality.CODE);
        }

        @Override
        public Set<SearchDomain> domains() {
            return Set.of(SearchDomain.GENERAL, SearchDomain.NEWS, SearchDomain.ACADEMIC, SearchDomain.CODE);
        }

        @Override
        public Set<SearchTier> tiers() {
            return Set.of(SearchTier.NORMAL, SearchTier.EXPERT);
        }

        @Override
        public ProviderAvailability availability(SearchScope scope) {
            // Unlike the other keyless protocols there is no built-in URL
            // fallback: a metasearch instance is somebody's server, and an
            // instance that was never given one cannot be guessed.
            return StringUtils.isBlank(cfg.baseUrl()) ? ProviderAvailability.DISABLED : ProviderAvailability.READY;
        }

        @Override
        public Optional<QuotaStatus> currentQuota(SearchScope scope) {
            // The instance has no quota to spend — it aggregates public
            // engines. What it does have is rate limits of its own, and those
            // surface as 429 with Retry-After like everywhere else.
            return Optional.empty();
        }

        @Override
        public String statusText(SearchScope scope) {
            if (StringUtils.isBlank(cfg.baseUrl())) {
                return "no instance URL configured";
            }
            return "self-hosted at " + cfg.baseUrl();
        }

        @Override
        public String promptHint() {
            return "SearXNG metasearch — a self-hosted instance that "
                    + "aggregates public engines (Google, Bing, DuckDuckGo, "
                    + "Wikipedia, arXiv, …, whatever the instance has "
                    + "enabled). No account and no per-query cost; result "
                    + "quality follows the instance's engine mix. Covers web, "
                    + "news, images, videos, PDFs/files, science and IT "
                    + "categories. Best as a broad, cheap net and as the "
                    + "fallback when credited sources are exhausted. Query "
                    + "style: ordinary keyword queries; use the expert "
                    + "filters `language`, `time_range` (day/week/month/year), "
                    + "`pageno` and `safesearch` when needed.";
        }

        @Override
        public SearchResult search(SearchRequest req, SearchScope scope) {
            String category = categoryFor(req.modality());
            if (category == null) {
                return SearchResult.softFailure(
                        req, cfg.instanceId(), "SearXNG does not serve modality " + req.modality());
            }
            Map<String, String> params = new LinkedHashMap<>();
            params.put("q", req.query());
            params.put("format", "json");
            params.put("categories", category);
            params.put("pageno", expertString(req, "pageno", "1"));
            String language = expertString(req, "language", cfg.extra("language", ""));
            if (!language.isEmpty()) params.put("language", language);
            String timeRange = expertString(req, "time_range", "");
            if (!timeRange.isEmpty()) params.put("time_range", timeRange);
            String safeSearch = expertString(req, "safesearch", cfg.extra("safesearch", ""));
            if (!safeSearch.isEmpty()) params.put("safesearch", safeSearch);

            String url = SimpleHttpClient.buildQuery(
                    URI.create(StringUtils.isBlank(cfg.baseUrl()) ? "" : cfg.baseUrl() + "/search"), params);
            JsonResponse response;
            try {
                response = http.get(URI.create(url), Map.of("User-Agent", USER_AGENT), TIMEOUT);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while calling SearXNG '" + cfg.instanceId() + "'", ie);
            } catch (Exception e) {
                throw new RuntimeException("SearXNG '" + cfg.instanceId() + "' call failed: " + e.getMessage(), e);
            }
            if (response.statusCode() != 200) {
                throw ProtocolErrors.httpFailure(
                        objectMapper, "SearXNG '" + cfg.instanceId() + "' /search", response, null);
            }
            if (looksLikeHtml(response.body())) {
                // A configuration state, not an outage: the instance answers
                // the page, not the API. Soft failure — no cooldown — with the
                // sentence that tells the operator what to change.
                return SearchResult.softFailure(
                        req,
                        cfg.instanceId(),
                        "SearXNG '" + cfg.instanceId() + "' does not serve JSON — "
                                + "enable format=json on the instance "
                                + "(formats: [html, json] in settings.yml)");
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

        private static boolean looksLikeHtml(String body) {
            String trimmed = body == null ? "" : body.stripLeading();
            return trimmed.startsWith("<");
        }

        /** SearXNG category for a modality, or null when the instance has none. */
        static @Nullable String categoryFor(SearchModality modality) {
            return switch (modality) {
                case WEB -> "general";
                case NEWS -> "news";
                case IMAGE -> "images";
                case VIDEO -> "videos";
                case PDF -> "files";
                case ACADEMIC -> "science";
                case CODE -> "it";
                default -> null;
            };
        }

        private static String expertString(SearchRequest req, String key, String fallback) {
            Object value = req.expertParams().get(key);
            if (value == null) {
                return fallback;
            }
            String text = String.valueOf(value).trim();
            return text.isEmpty() ? fallback : text;
        }

        List<SearchHit> parseHits(String json, SearchModality modality) {
            JsonNode root;
            try {
                root = objectMapper.readTree(json);
            } catch (RuntimeException e) {
                log.warn("SearXNG '{}': response is not JSON: {}", cfg.instanceId(), e.toString());
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

        private @Nullable SearchHit parseOneHit(JsonNode item, SearchModality modality) {
            String url = item.path("url").asText("");
            if (StringUtils.isBlank(url)) {
                return null;
            }
            String title = item.path("title").asText("");
            if (StringUtils.isBlank(title)) {
                title = url;
            }
            String content = item.path("content").asText("");

            Map<String, Object> extras = new LinkedHashMap<>();
            String publishedDate = item.path("publishedDate").asText("");
            if (!StringUtils.isBlank(publishedDate)) {
                extras.put("publishedDate", publishedDate);
            }
            String imageUrl = item.path("img_src").asText("");
            if (StringUtils.isBlank(imageUrl)) {
                imageUrl = item.path("thumbnail").asText("");
            }
            if (!StringUtils.isBlank(imageUrl)) {
                extras.put("imageUrl", imageUrl);
            }
            String engine = item.path("engine").asText("");
            if (!StringUtils.isBlank(engine)) {
                extras.put("engine", engine);
            }
            String category = item.path("category").asText("");
            if (!StringUtils.isBlank(category)) {
                extras.put("category", category);
            }

            return new SearchHit(
                    title,
                    url,
                    StringUtils.isBlank(content) ? null : content,
                    StringUtils.isBlank(engine) ? "SearXNG" : engine,
                    modality,
                    // No body channel: a SearXNG result *is* its snippet —
                    // surfacing the same text as `body` would pay for it twice.
                    null,
                    extras);
        }
    }
}
