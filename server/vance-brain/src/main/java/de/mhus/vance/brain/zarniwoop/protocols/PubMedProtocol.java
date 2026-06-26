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
 * PubMed search via the NCBI E-utilities API. Two synchronous calls
 * per request:
 *
 * <ol>
 *   <li>{@code esearch.fcgi?db=pubmed&term=…&retmode=json} → PMID list.</li>
 *   <li>{@code esummary.fcgi?db=pubmed&id=…&retmode=json} → per-PMID
 *       metadata (title, authors, journal, pubdate, DOI, PMCID).</li>
 * </ol>
 *
 * <p>Free + key-less. {@code &api_key=…} (optional via extras) lifts
 * the rate limit from 3 req/sec to 10 req/sec. NCBI asks every caller
 * to identify itself via {@code &tool=Vance&email=<owner>} — the
 * {@code contactEmail} extra wires through that.
 *
 * <p>Abstracts are deferred to a possible v2 (efetch returns XML, not
 * JSON, and would be a separate call per hit). v1 surfaces title +
 * authors + journal + pubdate, which is what the evaluate-recipe needs
 * to score relevance against a biomedical question.
 */
@Component
@Slf4j
public class PubMedProtocol implements SearchProtocol {

    public static final String ID = "pubmed";
    private static final String USER_AGENT = "Vance-Zarniwoop/0.1 (+https://github.com/mhus/vance)";

    private final ObjectMapper objectMapper;
    private final SimpleHttpClient http;

    @Autowired
    public PubMedProtocol(ObjectMapper objectMapper) {
        this(objectMapper, new SimpleHttpClient.JdkSimpleHttpClient());
    }

    PubMedProtocol(ObjectMapper objectMapper, SimpleHttpClient http) {
        this.objectMapper = objectMapper;
        this.http = http;
    }

    @Override public String id() { return ID; }
    @Override public String displayName() { return "PubMed (NCBI E-utilities)"; }
    @Override public Set<SearchModality> modalitiesSupported() { return Set.of(SearchModality.ACADEMIC); }
    @Override public Set<SearchTier> tiersSupported() {
        return Set.of(SearchTier.NORMAL, SearchTier.EXPERT);
    }

    @Override
    public SearchProviderInstance instantiate(ProviderInstanceConfig cfg) {
        if (cfg == null) throw new IllegalArgumentException("cfg is required");
        if (!ID.equals(cfg.protocolId())) {
            throw new IllegalArgumentException(
                    "PubMedProtocol cannot instantiate config with protocol '"
                            + cfg.protocolId() + "'");
        }
        return new PubMedInstance(cfg, objectMapper, http);
    }

    // ── Instance ─────────────────────────────────────────────────────

    static final class PubMedInstance implements SearchProviderInstance {

        private static final Duration TIMEOUT = Duration.ofSeconds(20);
        private static final int DEFAULT_NUM = 5;
        private static final int MAX_NUM = 25;

        private final ProviderInstanceConfig cfg;
        private final ObjectMapper objectMapper;
        private final SimpleHttpClient http;

        PubMedInstance(ProviderInstanceConfig cfg,
                       ObjectMapper objectMapper,
                       SimpleHttpClient http) {
            this.cfg = cfg;
            this.objectMapper = objectMapper;
            this.http = http;
        }

        @Override public String id() { return cfg.instanceId(); }
        @Override public String displayName() { return "PubMed (" + cfg.instanceId() + ")"; }
        @Override public Set<SearchModality> modalities() { return Set.of(SearchModality.ACADEMIC); }
        @Override public Set<SearchDomain> domains() { return Set.of(SearchDomain.ACADEMIC); }
        @Override public Set<SearchTier> tiers() {
            return Set.of(SearchTier.NORMAL, SearchTier.EXPERT);
        }

        @Override
        public ProviderAvailability availability(SearchScope scope) {
            // PubMed ships with a hard-coded NCBI fallback in baseUrl(),
            // so a blank cfg.baseUrl() (the default form state when the
            // user didn't override) still resolves to a working URL.
            return ProviderAvailability.READY;
        }

        @Override public Optional<QuotaStatus> currentQuota(SearchScope scope) {
            return Optional.empty();
        }

        @Override
        public String statusText(SearchScope scope) {
            if (!StringUtils.isBlank(apiKey())) {
                return "10 req/sec (api_key configured)";
            }
            String mail = contactEmail();
            if (StringUtils.isBlank(mail)) {
                return "3 req/sec, no contactEmail set";
            }
            return "3 req/sec, contact " + mail;
        }

        @Override
        public String promptHint() {
            return "PubMed / MEDLINE — National Library of Medicine "
                    + "biomedical literature index, ~37M citations. "
                    + "Indexed content: life sciences, clinical medicine, "
                    + "pharmacology, public health, nursing, dentistry, "
                    + "veterinary, biochemistry, molecular biology, "
                    + "neuroscience. Per-paper title, authors, journal, "
                    + "publication date, DOI, PubMed ID. Best for: "
                    + "biomedical / clinical literature, drug trials, "
                    + "disease mechanisms, anything with MeSH-indexed "
                    + "terminology. Query style: free-text terms — "
                    + "PubMed auto-translates to MeSH-Headings + "
                    + "All-Fields, so plain English biomedical phrases "
                    + "work well (`crispr gene editing`, "
                    + "`semaglutide cardiovascular outcomes`). Abstracts "
                    + "are NOT inline in v1 — title + authors + journal "
                    + "only; deepen via efetch is reserved for v2.";
        }

        @Override
        public SearchResult search(SearchRequest req, SearchScope scope) {
            if (req.modality() != SearchModality.ACADEMIC) {
                return softFailure(req, "modality " + req.modality()
                        + " not supported by PubMed '" + cfg.instanceId() + "'");
            }
            int num = clampNum(req.maxResults());
            List<String> pmids = esearch(req.query(), num);
            if (pmids.isEmpty()) {
                return new SearchResult(
                        req.query(), req.modality(), cfg.instanceId(), req.tier(),
                        List.of(), 0, 0, null, null, Map.of());
            }
            List<SearchHit> hits = esummary(pmids);
            return new SearchResult(
                    req.query(), req.modality(), cfg.instanceId(), req.tier(),
                    hits, hits.size(), 0, null, null, Map.of());
        }

        // ── Step 1 — esearch ────────────────────────────────────────

        List<String> esearch(String query, int num) {
            Map<String, String> params = SimpleHttpClient.mapOf(
                    "db", "pubmed",
                    "term", query,
                    "retmode", "json",
                    "retmax", String.valueOf(num),
                    "tool", "Vance");
            String mail = contactEmail();
            if (!StringUtils.isBlank(mail)) params.put("email", mail);
            String key = apiKey();
            if (!StringUtils.isBlank(key)) params.put("api_key", key);

            String url = SimpleHttpClient.buildQuery(
                    URI.create(baseUrl() + "/esearch.fcgi"), params);
            Response response = call(url, "esearch");
            try {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode idList = root.path("esearchresult").path("idlist");
                if (!idList.isArray()) return List.of();
                List<String> out = new ArrayList<>(idList.size());
                for (JsonNode id : idList) {
                    String s = id.asText("");
                    if (!StringUtils.isBlank(s)) out.add(s);
                }
                return out;
            } catch (Exception e) {
                log.warn("PubMed '{}': esearch parse failed: {}",
                        cfg.instanceId(), e.toString());
                return List.of();
            }
        }

        // ── Step 2 — esummary ───────────────────────────────────────

        List<SearchHit> esummary(List<String> pmids) {
            Map<String, String> params = SimpleHttpClient.mapOf(
                    "db", "pubmed",
                    "id", String.join(",", pmids),
                    "retmode", "json",
                    "tool", "Vance");
            String mail = contactEmail();
            if (!StringUtils.isBlank(mail)) params.put("email", mail);
            String key = apiKey();
            if (!StringUtils.isBlank(key)) params.put("api_key", key);

            String url = SimpleHttpClient.buildQuery(
                    URI.create(baseUrl() + "/esummary.fcgi"), params);
            Response response = call(url, "esummary");
            return parseHits(response.body(), pmids);
        }

        List<SearchHit> parseHits(String json, List<String> pmidOrder) {
            try {
                JsonNode result = objectMapper.readTree(json).path("result");
                if (result.isMissingNode() || !result.isObject()) return List.of();
                List<SearchHit> out = new ArrayList<>(pmidOrder.size());
                for (String pmid : pmidOrder) {
                    JsonNode item = result.path(pmid);
                    if (item.isMissingNode() || !item.isObject()) continue;
                    String title = normaliseText(item.path("title").asText(""));
                    if (StringUtils.isBlank(title)) continue;

                    String journal = item.path("source").asText("");
                    String pubdate = item.path("pubdate").asText("");
                    int year = parseYear(pubdate);
                    String authors = collectAuthors(item.path("authors"));
                    String doi = pickArticleId(item.path("articleids"), "doi");
                    String pmcid = pickArticleId(item.path("articleids"), "pmc");
                    String volume = item.path("volume").asText("");
                    List<String> pubtypes = collectPubTypes(item.path("pubtype"));

                    Map<String, Object> extras = new LinkedHashMap<>();
                    extras.put("pmid", pmid);
                    if (!StringUtils.isBlank(authors)) extras.put("authors", authors);
                    if (year > 0) extras.put("publicationYear", year);
                    if (!StringUtils.isBlank(journal)) extras.put("venue", journal);
                    if (!StringUtils.isBlank(doi)) extras.put("doi", doi);
                    if (!StringUtils.isBlank(pmcid)) extras.put("pmcid", pmcid);
                    if (!StringUtils.isBlank(volume)) extras.put("volume", volume);
                    if (!pubtypes.isEmpty()) extras.put("pubtypes", pubtypes);

                    out.add(new SearchHit(
                            title,
                            "https://pubmed.ncbi.nlm.nih.gov/" + pmid + "/",
                            composeSnippet(authors, year, journal),
                            "PubMed",
                            SearchModality.ACADEMIC, null, extras));
                }
                return out;
            } catch (Exception e) {
                log.warn("PubMed '{}': parseHits failed: {}",
                        cfg.instanceId(), e.toString());
                return List.of();
            }
        }

        // ── HTTP wrapper ────────────────────────────────────────────

        private Response call(String url, String step) {
            try {
                Response r = http.get(URI.create(url), USER_AGENT, TIMEOUT);
                if (r.statusCode() != 200) {
                    throw new RuntimeException("PubMed '" + cfg.instanceId()
                            + "' " + step + " returned HTTP " + r.statusCode());
                }
                return r;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(
                        "Interrupted while calling PubMed '" + cfg.instanceId() + "'");
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                throw new RuntimeException(
                        "PubMed '" + cfg.instanceId() + "' " + step
                                + " call failed: " + e.getMessage(), e);
            }
        }

        // ── JSON helpers ────────────────────────────────────────────

        private static String collectAuthors(JsonNode authors) {
            if (authors == null || !authors.isArray() || authors.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            int kept = 0;
            for (JsonNode a : authors) {
                if (kept >= 3) {
                    sb.append(", et al.");
                    break;
                }
                String name = a.path("name").asText("");
                if (StringUtils.isBlank(name)) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(name);
                kept++;
            }
            return sb.toString();
        }

        private static String pickArticleId(JsonNode articleIds, String idtype) {
            if (articleIds == null || !articleIds.isArray()) return "";
            for (JsonNode id : articleIds) {
                if (idtype.equals(id.path("idtype").asText(""))) {
                    return id.path("value").asText("");
                }
            }
            return "";
        }

        private static List<String> collectPubTypes(JsonNode pubtype) {
            if (pubtype == null || !pubtype.isArray() || pubtype.isEmpty()) return List.of();
            List<String> out = new ArrayList<>(pubtype.size());
            for (JsonNode t : pubtype) {
                String s = t.asText("");
                if (!StringUtils.isBlank(s)) out.add(s);
            }
            return out;
        }

        private static String composeSnippet(String authors, int year, String journal) {
            StringBuilder sb = new StringBuilder();
            if (!StringUtils.isBlank(authors)) sb.append(authors);
            if (year > 0) {
                if (sb.length() > 0) sb.append(" — ");
                sb.append(year);
            }
            if (!StringUtils.isBlank(journal)) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(journal);
            }
            return sb.length() == 0 ? null : sb.toString();
        }

        private static int parseYear(String pubdate) {
            if (StringUtils.isBlank(pubdate) || pubdate.length() < 4) return 0;
            try {
                return Integer.parseInt(pubdate.substring(0, 4));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private static String normaliseText(String s) {
            if (s == null) return "";
            return s.replaceAll("\\s+", " ").trim();
        }

        private String contactEmail() {
            Object raw = cfg.extras().get("contactEmail");
            return raw == null ? "" : raw.toString().trim();
        }

        private String apiKey() {
            Object raw = cfg.extras().get("apiKey");
            return raw == null ? "" : raw.toString().trim();
        }

        private String baseUrl() {
            String base = cfg.baseUrl();
            if (StringUtils.isBlank(base)) base = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils";
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
