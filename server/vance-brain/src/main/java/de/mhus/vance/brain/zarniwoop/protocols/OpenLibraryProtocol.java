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
 * OpenLibrary book search. Free, key-less JSON endpoint:
 * {@code https://openlibrary.org/search.json?q=…}.
 *
 * <p>Maps onto {@link SearchModality#BOOK}. Each hit links to the
 * work page on openlibrary.org; the {@code extras} carry the
 * first-publish year, ISBNs, and a thumbnail cover when available
 * (cover-id resolves to {@code /b/id/<id>-M.jpg}).
 */
@Component
@Slf4j
public class OpenLibraryProtocol implements SearchProtocol {

    public static final String ID = "openlibrary";
    private static final String USER_AGENT = "Vance-Zarniwoop/0.1 (+https://github.com/mhus/vance)";

    private final ObjectMapper objectMapper;
    private final SimpleHttpClient http;

    @Autowired
    public OpenLibraryProtocol(ObjectMapper objectMapper) {
        this(objectMapper, new SimpleHttpClient.JdkSimpleHttpClient());
    }

    OpenLibraryProtocol(ObjectMapper objectMapper, SimpleHttpClient http) {
        this.objectMapper = objectMapper;
        this.http = http;
    }

    @Override public String id() { return ID; }
    @Override public String displayName() { return "OpenLibrary"; }
    @Override
    public Set<SearchModality> modalitiesSupported() {
        return Set.of(SearchModality.BOOK);
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
                    "OpenLibraryProtocol cannot instantiate config with protocol '"
                            + cfg.protocolId() + "'");
        }
        return new OpenLibraryInstance(cfg, objectMapper, http);
    }

    // ── Instance ─────────────────────────────────────────────────────

    static final class OpenLibraryInstance implements SearchProviderInstance {

        private static final Duration TIMEOUT = Duration.ofSeconds(15);
        private static final int DEFAULT_NUM = 5;
        private static final int MAX_NUM = 25;

        private final ProviderInstanceConfig cfg;
        private final ObjectMapper objectMapper;
        private final SimpleHttpClient http;

        OpenLibraryInstance(ProviderInstanceConfig cfg,
                            ObjectMapper objectMapper,
                            SimpleHttpClient http) {
            this.cfg = cfg;
            this.objectMapper = objectMapper;
            this.http = http;
        }

        @Override public String id() { return cfg.instanceId(); }
        @Override public String displayName() { return "OpenLibrary (" + cfg.instanceId() + ")"; }
        @Override public Set<SearchModality> modalities() { return Set.of(SearchModality.BOOK); }
        @Override public Set<SearchDomain> domains() { return Set.of(SearchDomain.BOOK); }
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
            return Optional.empty();
        }

        @Override
        public String statusText(SearchScope scope) {
            return "no quota meter (free OpenLibrary endpoint)";
        }

        @Override
        public SearchResult search(SearchRequest req, SearchScope scope) {
            if (req.modality() != SearchModality.BOOK) {
                return softFailure(req, "modality " + req.modality()
                        + " not supported by OpenLibrary '" + cfg.instanceId() + "'");
            }
            int num = clampNum(req.maxResults());
            String url = SimpleHttpClient.buildQuery(
                    URI.create(baseUrl() + "/search.json"),
                    SimpleHttpClient.mapOf(
                            "q", req.query(),
                            "limit", String.valueOf(num)));
            Response response;
            try {
                response = http.get(URI.create(url), USER_AGENT, TIMEOUT);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(
                        "Interrupted while calling OpenLibrary '" + cfg.instanceId() + "'");
            } catch (Exception e) {
                throw new RuntimeException(
                        "OpenLibrary '" + cfg.instanceId() + "' call failed: " + e.getMessage(), e);
            }
            if (response.statusCode() != 200) {
                throw new RuntimeException("OpenLibrary '" + cfg.instanceId()
                        + "' returned HTTP " + response.statusCode());
            }
            List<SearchHit> hits = parseHits(response.body());
            return new SearchResult(
                    req.query(), req.modality(), cfg.instanceId(), req.tier(),
                    hits, hits.size(), 0, null, null, Map.of());
        }

        List<SearchHit> parseHits(String json) {
            try {
                JsonNode root = objectMapper.readTree(json);
                JsonNode docs = root.path("docs");
                List<SearchHit> out = new ArrayList<>();
                if (!docs.isArray()) return out;
                for (JsonNode item : docs) {
                    String title = item.path("title").asText("");
                    if (StringUtils.isBlank(title)) continue;
                    String key = item.path("key").asText("");
                    String url = StringUtils.isBlank(key)
                            ? null
                            : "https://openlibrary.org" + (key.startsWith("/") ? key : "/" + key);
                    if (url == null) continue;
                    Map<String, Object> extras = new LinkedHashMap<>();
                    extras.put("workKey", key);
                    String author = pickFirstString(item.path("author_name"));
                    if (!StringUtils.isBlank(author)) extras.put("author", author);
                    int year = item.path("first_publish_year").asInt(0);
                    if (year > 0) extras.put("firstPublishYear", year);
                    String publisher = pickFirstString(item.path("publisher"));
                    if (!StringUtils.isBlank(publisher)) extras.put("publisher", publisher);
                    String isbn = pickFirstString(item.path("isbn"));
                    if (!StringUtils.isBlank(isbn)) extras.put("isbn", isbn);
                    int coverId = item.path("cover_i").asInt(0);
                    if (coverId > 0) {
                        extras.put("coverThumbnailUrl",
                                "https://covers.openlibrary.org/b/id/" + coverId + "-M.jpg");
                    }
                    int editionCount = item.path("edition_count").asInt(0);
                    if (editionCount > 0) extras.put("editionCount", editionCount);
                    String subtitle = item.path("subtitle").asText("");

                    String snippet = composeSnippet(author, year, subtitle);
                    out.add(new SearchHit(
                            title, url,
                            StringUtils.isBlank(snippet) ? null : snippet,
                            "OpenLibrary",
                            SearchModality.BOOK, null, extras));
                }
                return out;
            } catch (Exception e) {
                log.warn("OpenLibrary '{}': parseHits failed: {}", cfg.instanceId(), e.toString());
                return List.of();
            }
        }

        private static String pickFirstString(JsonNode array) {
            if (array == null || !array.isArray() || array.isEmpty()) return "";
            return array.get(0).asText("");
        }

        private static String composeSnippet(String author, int year, String subtitle) {
            StringBuilder sb = new StringBuilder();
            if (!StringUtils.isBlank(author)) sb.append("by ").append(author);
            if (year > 0) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(year);
            }
            if (!StringUtils.isBlank(subtitle)) {
                if (sb.length() > 0) sb.append(" — ");
                sb.append(subtitle);
            }
            return sb.toString();
        }

        private String baseUrl() {
            String base = cfg.baseUrl();
            if (StringUtils.isBlank(base)) base = "https://openlibrary.org";
            return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        }

        private SearchResult softFailure(SearchRequest req, String message) {
            return new SearchResult(
                    req.query(), req.modality(), cfg.instanceId(), req.tier(),
                    List.of(), 0, 0, null, message, Map.of());
        }

        private static int clampNum(int requested) {
            if (requested < 1) return DEFAULT_NUM;
            if (requested > MAX_NUM) return MAX_NUM;
            return requested;
        }
    }
}
