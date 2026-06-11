package de.mhus.vance.brain.zarniwoop.protocols;

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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 * Wikipedia REST search adapter. Queries the search endpoint
 * ({@code /w/api.php?action=query&list=search}), then pulls the
 * extract for the top hits via the page-summary REST endpoint and
 * returns it inline as {@link ContentInline#EMBED_TEXT}.
 *
 * <p>No API key required — Wikipedia is happy to serve anonymous
 * requests with a polite User-Agent.
 *
 * <p>One instance per project, scoped to one MediaWiki host
 * ({@code de.wikipedia.org}, {@code en.wikipedia.org}, an internal
 * MediaWiki mirror). The host is the {@code baseUrl} of the endpoint
 * config; the {@code /w/api.php} suffix is appended by the instance.
 */
@Component
@Slf4j
public class WikipediaProtocol implements SearchProtocol {

    public static final String ID = "wikipedia";

    private final ObjectMapper objectMapper;
    private final WikipediaHttp http;

    @Autowired
    public WikipediaProtocol(ObjectMapper objectMapper) {
        this(objectMapper, new WikipediaHttp.JdkWikipediaHttp());
    }

    /** Test-seam constructor. */
    WikipediaProtocol(ObjectMapper objectMapper, WikipediaHttp http) {
        this.objectMapper = objectMapper;
        this.http = http;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Wikipedia (REST search + extracts)";
    }

    @Override
    public Set<SearchModality> modalitiesSupported() {
        return Set.of(SearchModality.WEB, SearchModality.ENCYCLOPEDIA);
    }

    @Override
    public Set<SearchTier> tiersSupported() {
        return Set.of(SearchTier.NORMAL, SearchTier.EXPERT);
    }

    @Override
    public SearchProviderInstance instantiate(ProviderInstanceConfig cfg) {
        if (cfg == null) {
            throw new IllegalArgumentException("cfg is required");
        }
        if (!ID.equals(cfg.protocolId())) {
            throw new IllegalArgumentException(
                    "WikipediaProtocol cannot instantiate config with protocol '"
                            + cfg.protocolId() + "'");
        }
        return new WikipediaInstance(cfg, objectMapper, http);
    }

    /** HTTP test-seam — same shape as {@link SerperHttpClient} but no key header. */
    interface WikipediaHttp {

        record Response(int statusCode, String body) { }

        Response get(URI url, Duration timeout) throws Exception;

        final class JdkWikipediaHttp implements WikipediaHttp {

            private final HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            @Override
            public Response get(URI url, Duration timeout) throws Exception {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(url)
                        .header("User-Agent", "Vance-Zarniwoop/0.1 (+https://github.com/mhus/vance)")
                        .header("Accept", "application/json")
                        .timeout(timeout)
                        .GET()
                        .build();
                HttpResponse<String> r = client.send(
                        request, HttpResponse.BodyHandlers.ofString());
                return new Response(r.statusCode(), r.body() == null ? "" : r.body());
            }
        }
    }

    // ── Instance ──────────────────────────────────────────────────────

    static final class WikipediaInstance implements SearchProviderInstance {

        private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
        private static final int DEFAULT_NUM = 5;
        private static final int MAX_NUM = 10;
        private static final int EXTRACT_BUDGET = 3;   // pages to fetch extracts for

        private final ProviderInstanceConfig cfg;
        private final ObjectMapper objectMapper;
        private final WikipediaHttp http;

        WikipediaInstance(ProviderInstanceConfig cfg,
                          ObjectMapper objectMapper,
                          WikipediaHttp http) {
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
            return "Wikipedia (" + cfg.instanceId() + ")";
        }

        @Override
        public Set<SearchModality> modalities() {
            // Wikipedia is the encyclopedia source in the inventory and
            // also doubles as a WEB-fallback when other web instances die.
            return Set.of(SearchModality.WEB, SearchModality.ENCYCLOPEDIA);
        }

        @Override
        public Set<SearchDomain> domains() {
            return Set.of(SearchDomain.ENCYCLOPEDIA, SearchDomain.GENERAL);
        }

        @Override
        public Set<SearchTier> tiers() {
            // EXPERT means "callers may pin this instance"; the
            // protocol silently ignores filter params it doesn't
            // implement (no site/dateRange semantics on MediaWiki).
            // Without EXPERT in the set, ZarniwoopResearchService
            // can't honour a planned step with instance="wiki-de"
            // — see planning/zarniwoop-service.md §11.
            return Set.of(SearchTier.NORMAL, SearchTier.EXPERT);
        }

        @Override
        public ProviderAvailability availability(SearchScope scope) {
            return StringUtils.isBlank(cfg.baseUrl())
                    ? ProviderAvailability.DISABLED
                    : ProviderAvailability.READY;
        }

        @Override
        public Optional<QuotaStatus> currentQuota(SearchScope scope) {
            return Optional.empty();
        }

        @Override
        public String promptHint() {
            return "Wikipedia (one MediaWiki host per endpoint). Indexed "
                    + "content: curated encyclopedia articles — established "
                    + "topics, biographies, historical events, geography, "
                    + "scientific concepts, organisations, cultural and "
                    + "art-historical subjects. The article extract is "
                    + "returned inline with each hit. Best for: getting "
                    + "neutral overview text on a topic that has "
                    + "stabilised enough to have its own article, factual "
                    + "encyclopedia-style background, biographical / "
                    + "geographical / historical lookups. Query style: "
                    + "the article subject as a noun phrase, the way an "
                    + "article would be titled — `Rote und Blaue "
                    + "Mauritius`, `Patch Tuesday`, `Albert Einstein`, "
                    + "`Holzgerechtigkeit`. Match the endpoint's host "
                    + "language (wiki-de → German subject, "
                    + "wiki-en → English subject). The host language "
                    + "matches the endpoint id.";
        }

        @Override
        public SearchResult search(SearchRequest req, SearchScope scope) {
            if (req.modality() != SearchModality.WEB
                    && req.modality() != SearchModality.ENCYCLOPEDIA) {
                return softFailure(req, "modality " + req.modality()
                        + " not supported by Wikipedia '" + cfg.instanceId() + "'");
            }
            int num = clampNum(req.maxResults());
            URI searchUri = URI.create(apiUrl()
                    + "?action=query&list=search&format=json"
                    + "&srsearch=" + encode(req.query())
                    + "&srlimit=" + num);
            WikipediaHttp.Response response;
            try {
                response = http.get(searchUri, REQUEST_TIMEOUT);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(
                        "Interrupted while calling Wikipedia '" + cfg.instanceId() + "'");
            } catch (Exception e) {
                throw new RuntimeException(
                        "Wikipedia '" + cfg.instanceId() + "' call failed: " + e.getMessage(), e);
            }
            if (response.statusCode() != 200) {
                throw new RuntimeException("Wikipedia '" + cfg.instanceId() + "' returned HTTP "
                        + response.statusCode());
            }
            List<RawHit> raw = parseSearch(response.body());
            // Best-effort extract for the top N — failures are silent;
            // hit still goes back with title/url/snippet only.
            int extractsLeft = Math.min(EXTRACT_BUDGET, raw.size());
            List<SearchHit> hits = new ArrayList<>(raw.size());
            for (RawHit r : raw) {
                ContentReference content = null;
                if (extractsLeft > 0) {
                    String extract = tryFetchExtract(r.title);
                    if (!StringUtils.isBlank(extract)) {
                        content = new ContentReference(
                                cfg.instanceId() + ":hit:" + UUID.randomUUID(),
                                "text/plain",
                                extract.length(),
                                ContentInline.EMBED_TEXT,
                                extract,
                                null);
                    }
                    extractsLeft--;
                }
                Map<String, Object> extras = new LinkedHashMap<>();
                if (r.snippet != null && !r.snippet.isEmpty()) extras.put("snippet", r.snippet);
                hits.add(new SearchHit(
                        r.title,
                        pageUrl(r.title),
                        r.snippet == null ? null : r.snippet,
                        "Wikipedia",
                        SearchModality.WEB,
                        content,
                        extras));
            }
            return new SearchResult(
                    req.query(), req.modality(), cfg.instanceId(), req.tier(),
                    hits, hits.size(), 0, null, null, Map.of());
        }

        private String tryFetchExtract(String title) {
            URI uri = URI.create(summaryUrl(title));
            try {
                WikipediaHttp.Response r = http.get(uri, Duration.ofSeconds(5));
                if (r.statusCode() != 200) return null;
                JsonNode root = objectMapper.readTree(r.body());
                String extract = root.path("extract").asText("");
                return StringUtils.isBlank(extract) ? null : extract;
            } catch (Exception e) {
                log.debug("Wikipedia '{}': extract fetch for '{}' failed: {}",
                        cfg.instanceId(), title, e.toString());
                return null;
            }
        }

        // ── parsing ───────────────────────────────────────────────────

        List<RawHit> parseSearch(String json) {
            try {
                JsonNode root = objectMapper.readTree(json);
                JsonNode arr = root.path("query").path("search");
                List<RawHit> out = new ArrayList<>();
                if (!arr.isArray()) return out;
                for (JsonNode item : arr) {
                    String title = item.path("title").asText("");
                    if (StringUtils.isBlank(title)) continue;
                    RawHit r = new RawHit();
                    r.title = title;
                    r.snippet = item.path("snippet").asText("").replaceAll("<[^>]+>", "");
                    out.add(r);
                }
                return out;
            } catch (Exception e) {
                log.warn("Wikipedia '{}': parseSearch failed: {}", cfg.instanceId(), e.toString());
                return List.of();
            }
        }

        // ── helpers ───────────────────────────────────────────────────

        private String apiUrl() {
            String base = cfg.baseUrl();
            if (StringUtils.isBlank(base)) {
                throw new RuntimeException("Wikipedia '" + cfg.instanceId() + "' has no baseUrl");
            }
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            // Tolerant: accept either a host base (https://de.wikipedia.org)
            // or a configured api.php path (https://de.wikipedia.org/w/api.php).
            return base.endsWith("api.php") ? base : base + "/w/api.php";
        }

        private String summaryUrl(String pageTitle) {
            String base = cfg.baseUrl();
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            // /w/api.php → strip down to host root for /api/rest_v1/...
            if (base.endsWith("/w/api.php")) {
                base = base.substring(0, base.length() - "/w/api.php".length());
            }
            return base + "/api/rest_v1/page/summary/" + encode(pageTitle);
        }

        private String pageUrl(String pageTitle) {
            String base = cfg.baseUrl();
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            if (base.endsWith("/w/api.php")) {
                base = base.substring(0, base.length() - "/w/api.php".length());
            }
            return base + "/wiki/" + encode(pageTitle.replace(' ', '_'));
        }

        private static String encode(String s) {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        }

        private static int clampNum(int requested) {
            if (requested < 1) return DEFAULT_NUM;
            if (requested > MAX_NUM) return MAX_NUM;
            return requested;
        }

        private SearchResult softFailure(SearchRequest req, String message) {
            return new SearchResult(
                    req.query(), req.modality(), cfg.instanceId(), req.tier(),
                    List.of(), 0, 0, null, message, Map.of());
        }

        static final class RawHit {
            String title = "";
            String snippet = "";
        }
    }
}
