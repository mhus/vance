package de.mhus.vance.brain.zarniwoop.protocols;

import de.mhus.vance.brain.zarniwoop.protocols.SimpleHttpClient.Response;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * HackerNews search via Algolia's public, key-free JSON endpoint:
 * {@code https://hn.algolia.com/api/v1/search?query=…&tags=story&hitsPerPage=N}.
 *
 * <p>Maps onto {@link SearchModality#NEWS} (tech-community
 * discussion as a news proxy) and {@link SearchModality#WEB}
 * (story-titles + URLs work as web hits too). Authors / points /
 * comment-count land in {@code extras} so downstream prompts can
 * surface community-signal numbers when relevant.
 */
@Component
@Slf4j
public class HackerNewsProtocol implements SearchProtocol {

    public static final String ID = "hackernews";
    private static final String USER_AGENT = "Vance-Zarniwoop/0.1 (+https://github.com/mhus/vance)";

    private final ObjectMapper objectMapper;
    private final SimpleHttpClient http;

    @Autowired
    public HackerNewsProtocol(ObjectMapper objectMapper) {
        this(objectMapper, new SimpleHttpClient.JdkSimpleHttpClient());
    }

    HackerNewsProtocol(ObjectMapper objectMapper, SimpleHttpClient http) {
        this.objectMapper = objectMapper;
        this.http = http;
    }

    @Override public String id() { return ID; }
    @Override public String displayName() { return "HackerNews (Algolia)"; }
    @Override
    public Set<SearchModality> modalitiesSupported() {
        return Set.of(SearchModality.NEWS, SearchModality.WEB);
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
                    "HackerNewsProtocol cannot instantiate config with protocol '"
                            + cfg.protocolId() + "'");
        }
        return new HackerNewsInstance(cfg, objectMapper, http);
    }

    // ── Instance ─────────────────────────────────────────────────────

    static final class HackerNewsInstance implements SearchProviderInstance {

        private static final Duration TIMEOUT = Duration.ofSeconds(10);
        private static final int DEFAULT_NUM = 5;
        private static final int MAX_NUM = 20;

        private final ProviderInstanceConfig cfg;
        private final ObjectMapper objectMapper;
        private final SimpleHttpClient http;

        HackerNewsInstance(ProviderInstanceConfig cfg,
                           ObjectMapper objectMapper,
                           SimpleHttpClient http) {
            this.cfg = cfg;
            this.objectMapper = objectMapper;
            this.http = http;
        }

        @Override public String id() { return cfg.instanceId(); }
        @Override public String displayName() { return "HackerNews (" + cfg.instanceId() + ")"; }
        @Override public Set<SearchModality> modalities() {
            return Set.of(SearchModality.NEWS, SearchModality.WEB);
        }
        @Override public Set<SearchDomain> domains() {
            return Set.of(SearchDomain.NEWS, SearchDomain.GENERAL, SearchDomain.CODE);
        }
        @Override public Set<SearchTier> tiers() {
            return Set.of(SearchTier.NORMAL, SearchTier.EXPERT);
        }

        @Override
        public ProviderAvailability availability(SearchScope scope) {
            return StringUtils.isBlank(cfg.baseUrl())
                    ? ProviderAvailability.DISABLED
                    : ProviderAvailability.READY;
        }

        @Override public Optional<QuotaStatus> currentQuota(SearchScope scope) {
            // Algolia HN API is unmetered for normal use; nothing to surface.
            return Optional.empty();
        }

        @Override
        public String statusText(SearchScope scope) {
            return "no quota meter (free Algolia endpoint)";
        }

        @Override
        public SearchResult search(SearchRequest req, SearchScope scope) {
            int num = clampNum(req.maxResults());
            String url = SimpleHttpClient.buildQuery(
                    URI.create(baseUrl() + "/search"),
                    SimpleHttpClient.mapOf(
                            "query", req.query(),
                            "tags", "story",
                            "hitsPerPage", String.valueOf(num)));
            Response response;
            try {
                response = http.get(URI.create(url), USER_AGENT, TIMEOUT);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(
                        "Interrupted while calling HN '" + cfg.instanceId() + "'");
            } catch (Exception e) {
                throw new RuntimeException(
                        "HN '" + cfg.instanceId() + "' call failed: " + e.getMessage(), e);
            }
            if (response.statusCode() != 200) {
                throw new RuntimeException("HN '" + cfg.instanceId() + "' returned HTTP "
                        + response.statusCode());
            }
            List<SearchHit> hits = parseHits(response.body(), req.modality());
            return new SearchResult(
                    req.query(), req.modality(), cfg.instanceId(), req.tier(),
                    hits, hits.size(), 0, null, null, Map.of());
        }

        List<SearchHit> parseHits(String json, SearchModality modality) {
            try {
                JsonNode root = objectMapper.readTree(json);
                JsonNode arr = root.path("hits");
                List<SearchHit> out = new ArrayList<>();
                if (!arr.isArray()) return out;
                for (JsonNode item : arr) {
                    String title = item.path("title").asText("");
                    String url = item.path("url").asText("");
                    String objectId = item.path("objectID").asText("");
                    if (StringUtils.isBlank(title)) continue;
                    // HN entries without an external URL are Ask/Show
                    // posts — link back to the HN item page.
                    if (StringUtils.isBlank(url)) {
                        if (StringUtils.isBlank(objectId)) continue;
                        url = "https://news.ycombinator.com/item?id=" + objectId;
                    }
                    Map<String, Object> extras = new LinkedHashMap<>();
                    String author = item.path("author").asText("");
                    if (!StringUtils.isBlank(author)) extras.put("author", author);
                    int points = item.path("points").asInt(0);
                    if (points > 0) extras.put("points", points);
                    int comments = item.path("num_comments").asInt(0);
                    if (comments > 0) extras.put("comments", comments);
                    String createdAt = item.path("created_at").asText("");
                    if (!StringUtils.isBlank(createdAt)) extras.put("createdAt", createdAt);
                    if (!StringUtils.isBlank(objectId)) {
                        extras.put("hnDiscussion",
                                "https://news.ycombinator.com/item?id=" + objectId);
                    }
                    out.add(new SearchHit(
                            title, url, null, "HackerNews", modality, null, extras));
                }
                return out;
            } catch (Exception e) {
                log.warn("HN '{}': parseHits failed: {}", cfg.instanceId(), e.toString());
                return List.of();
            }
        }

        private String baseUrl() {
            String base = cfg.baseUrl();
            if (StringUtils.isBlank(base)) base = "https://hn.algolia.com/api/v1";
            return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        }

        private static int clampNum(int requested) {
            if (requested < 1) return DEFAULT_NUM;
            if (requested > MAX_NUM) return MAX_NUM;
            return requested;
        }

        // Unused — kept for symmetry with the other instances. */
        @SuppressWarnings("unused")
        private Instant now() { return Instant.now(); }
    }
}
