package de.mhus.vance.brain.zarniwoop.protocols;

import de.mhus.vance.brain.zarniwoop.protocols.SimpleHttpClient.Response;
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
 * OpenAlex search for academic literature. Free, key-less:
 * {@code https://api.openalex.org/works?search=…&per-page=N}.
 *
 * <p>Adding a contact email lifts the rate limit to the "polite
 * pool" — read from the per-endpoint extra {@code contactEmail}
 * (e.g. {@code research.endpoint.openalex.contactEmail=me@x.de}).
 * Optional but recommended.
 *
 * <p>The abstract returned by OpenAlex is an
 * {@code abstract_inverted_index} (term → positions). The instance
 * reconstructs the readable abstract and surfaces it as an
 * {@link ContentInline#EMBED_TEXT} content reference so the
 * evaluate-recipe (and any caller) can see it without a follow-up
 * fetch.
 */
@Component
@Slf4j
public class OpenAlexProtocol implements SearchProtocol {

    public static final String ID = "openalex";
    private static final String USER_AGENT = "Vance-Zarniwoop/0.1 (+https://github.com/mhus/vance)";

    private final ObjectMapper objectMapper;
    private final SimpleHttpClient http;

    @Autowired
    public OpenAlexProtocol(ObjectMapper objectMapper) {
        this(objectMapper, new SimpleHttpClient.JdkSimpleHttpClient());
    }

    OpenAlexProtocol(ObjectMapper objectMapper, SimpleHttpClient http) {
        this.objectMapper = objectMapper;
        this.http = http;
    }

    @Override public String id() { return ID; }
    @Override public String displayName() { return "OpenAlex"; }
    @Override public Set<SearchModality> modalitiesSupported() { return Set.of(SearchModality.ACADEMIC); }
    @Override public Set<SearchTier> tiersSupported() {
        return Set.of(SearchTier.NORMAL, SearchTier.EXPERT);
    }

    @Override
    public SearchProviderInstance instantiate(ProviderInstanceConfig cfg) {
        if (cfg == null) throw new IllegalArgumentException("cfg is required");
        if (!ID.equals(cfg.protocolId())) {
            throw new IllegalArgumentException(
                    "OpenAlexProtocol cannot instantiate config with protocol '"
                            + cfg.protocolId() + "'");
        }
        return new OpenAlexInstance(cfg, objectMapper, http);
    }

    // ── Instance ─────────────────────────────────────────────────────

    static final class OpenAlexInstance implements SearchProviderInstance {

        private static final Duration TIMEOUT = Duration.ofSeconds(15);
        private static final int DEFAULT_NUM = 5;
        private static final int MAX_NUM = 25;

        private final ProviderInstanceConfig cfg;
        private final ObjectMapper objectMapper;
        private final SimpleHttpClient http;

        OpenAlexInstance(ProviderInstanceConfig cfg,
                         ObjectMapper objectMapper,
                         SimpleHttpClient http) {
            this.cfg = cfg;
            this.objectMapper = objectMapper;
            this.http = http;
        }

        @Override public String id() { return cfg.instanceId(); }
        @Override public String displayName() { return "OpenAlex (" + cfg.instanceId() + ")"; }
        @Override public Set<SearchModality> modalities() { return Set.of(SearchModality.ACADEMIC); }
        @Override public Set<SearchDomain> domains() { return Set.of(SearchDomain.ACADEMIC); }
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
            String mail = contactEmail();
            if (StringUtils.isBlank(mail)) {
                return "polite pool inactive (no contactEmail set)";
            }
            return "polite pool via " + mail;
        }

        @Override
        public SearchResult search(SearchRequest req, SearchScope scope) {
            if (req.modality() != SearchModality.ACADEMIC) {
                return softFailure(req, "modality " + req.modality()
                        + " not supported by OpenAlex '" + cfg.instanceId() + "'");
            }
            int num = clampNum(req.maxResults());
            Map<String, String> params = SimpleHttpClient.mapOf(
                    "search", req.query(),
                    "per-page", String.valueOf(num));
            String mail = contactEmail();
            if (!StringUtils.isBlank(mail)) {
                params.put("mailto", mail);
            }
            String url = SimpleHttpClient.buildQuery(
                    URI.create(baseUrl() + "/works"), params);
            Response response;
            try {
                response = http.get(URI.create(url), USER_AGENT, TIMEOUT);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(
                        "Interrupted while calling OpenAlex '" + cfg.instanceId() + "'");
            } catch (Exception e) {
                throw new RuntimeException(
                        "OpenAlex '" + cfg.instanceId() + "' call failed: " + e.getMessage(), e);
            }
            if (response.statusCode() != 200) {
                throw new RuntimeException("OpenAlex '" + cfg.instanceId()
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
                JsonNode results = root.path("results");
                List<SearchHit> out = new ArrayList<>();
                if (!results.isArray()) return out;
                for (JsonNode item : results) {
                    String title = item.path("title").asText("");
                    if (StringUtils.isBlank(title)) title = item.path("display_name").asText("");
                    if (StringUtils.isBlank(title)) continue;
                    String openalexId = item.path("id").asText("");
                    String url = pickPrimaryUrl(item, openalexId);
                    if (StringUtils.isBlank(url)) continue;
                    Map<String, Object> extras = new LinkedHashMap<>();
                    if (!StringUtils.isBlank(openalexId)) extras.put("openalexId", openalexId);
                    String doi = item.path("doi").asText("");
                    if (!StringUtils.isBlank(doi)) extras.put("doi", doi);
                    int year = item.path("publication_year").asInt(0);
                    if (year > 0) extras.put("publicationYear", year);
                    int citedBy = item.path("cited_by_count").asInt(0);
                    if (citedBy > 0) extras.put("citedByCount", citedBy);
                    String type = item.path("type").asText("");
                    if (!StringUtils.isBlank(type)) extras.put("workType", type);
                    String authors = collectAuthors(item.path("authorships"));
                    if (!StringUtils.isBlank(authors)) extras.put("authors", authors);
                    String venue = item.path("primary_location").path("source")
                            .path("display_name").asText("");
                    if (!StringUtils.isBlank(venue)) extras.put("venue", venue);
                    boolean openAccess = item.path("open_access").path("is_oa").asBoolean(false);
                    if (openAccess) extras.put("openAccess", true);

                    String snippet = composeSnippet(authors, year, venue);
                    String abstractText = reconstructAbstract(
                            item.path("abstract_inverted_index"));
                    ContentReference content = null;
                    if (!StringUtils.isBlank(abstractText)) {
                        content = new ContentReference(
                                cfg.instanceId() + ":hit:" + UUID.randomUUID(),
                                "text/plain",
                                abstractText.length(),
                                ContentInline.EMBED_TEXT,
                                abstractText,
                                null);
                    }
                    out.add(new SearchHit(
                            title, url,
                            StringUtils.isBlank(snippet) ? null : snippet,
                            "OpenAlex",
                            SearchModality.ACADEMIC, content, extras));
                }
                return out;
            } catch (Exception e) {
                log.warn("OpenAlex '{}': parseHits failed: {}", cfg.instanceId(), e.toString());
                return List.of();
            }
        }

        private static String pickPrimaryUrl(JsonNode item, String openalexId) {
            // Prefer DOI URL (clean, citable), fall back to OpenAlex id.
            String doi = item.path("doi").asText("");
            if (!StringUtils.isBlank(doi)) {
                return doi.startsWith("http") ? doi : "https://doi.org/" + doi;
            }
            JsonNode landingPage = item.path("primary_location").path("landing_page_url");
            if (landingPage.isTextual() && !landingPage.asText().isBlank()) {
                return landingPage.asText();
            }
            if (!StringUtils.isBlank(openalexId)) return openalexId;
            return "";
        }

        private static String collectAuthors(JsonNode authorships) {
            if (authorships == null || !authorships.isArray() || authorships.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (JsonNode a : authorships) {
                if (count >= 3) {
                    sb.append(", et al.");
                    break;
                }
                String name = a.path("author").path("display_name").asText("");
                if (StringUtils.isBlank(name)) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(name);
                count++;
            }
            return sb.toString();
        }

        private static String composeSnippet(String authors, int year, String venue) {
            StringBuilder sb = new StringBuilder();
            if (!StringUtils.isBlank(authors)) sb.append(authors);
            if (year > 0) {
                if (sb.length() > 0) sb.append(" — ");
                sb.append(year);
            }
            if (!StringUtils.isBlank(venue)) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(venue);
            }
            return sb.toString();
        }

        /**
         * OpenAlex stores abstracts as
         * {@code {"abstract_inverted_index": {"word": [positions, …]}}}.
         * Rebuild the readable sentence by sorting terms by position.
         */
        static String reconstructAbstract(JsonNode inverted) {
            if (inverted == null || !inverted.isObject() || inverted.isEmpty()) return "";
            int maxPos = -1;
            // First pass — determine the array length we need.
            for (Map.Entry<String, JsonNode> entry : inverted.properties()) {
                JsonNode positions = entry.getValue();
                if (!positions.isArray()) continue;
                for (JsonNode p : positions) {
                    int pos = p.asInt(-1);
                    if (pos > maxPos) maxPos = pos;
                }
            }
            if (maxPos < 0) return "";
            String[] words = new String[maxPos + 1];
            for (Map.Entry<String, JsonNode> entry : inverted.properties()) {
                String word = entry.getKey();
                JsonNode positions = entry.getValue();
                if (!positions.isArray()) continue;
                for (JsonNode p : positions) {
                    int pos = p.asInt(-1);
                    if (pos >= 0 && pos <= maxPos) words[pos] = word;
                }
            }
            StringBuilder sb = new StringBuilder();
            for (String w : words) {
                if (w == null) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(w);
            }
            return sb.toString();
        }

        private String contactEmail() {
            Object raw = cfg.extras().get("contactEmail");
            return raw == null ? "" : raw.toString().trim();
        }

        private String baseUrl() {
            String base = cfg.baseUrl();
            if (StringUtils.isBlank(base)) base = "https://api.openalex.org";
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
