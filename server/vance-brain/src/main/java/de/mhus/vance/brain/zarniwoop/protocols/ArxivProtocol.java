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
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * arXiv search via the Atom XML API:
 * {@code http://export.arxiv.org/api/query?search_query=all:…&max_results=N}.
 *
 * <p>The protocol response is Atom-1.0 XML, so the instance parses
 * with the JDK DOM parser instead of Jackson. Title + abstract +
 * author list survive the round-trip; the abstract lands in a
 * {@link ContentInline#EMBED_TEXT} content reference so the
 * evaluate-recipe sees it without a follow-up fetch.
 */
@Component
@Slf4j
public class ArxivProtocol implements SearchProtocol {

    public static final String ID = "arxiv";
    private static final String USER_AGENT = "Vance-Zarniwoop/0.1 (+https://github.com/mhus/vance)";

    private final SimpleHttpClient http;

    @Autowired
    public ArxivProtocol() {
        this(new SimpleHttpClient.JdkSimpleHttpClient());
    }

    ArxivProtocol(SimpleHttpClient http) {
        this.http = http;
    }

    @Override public String id() { return ID; }
    @Override public String displayName() { return "arXiv (Atom)"; }
    @Override public Set<SearchModality> modalitiesSupported() { return Set.of(SearchModality.ACADEMIC); }
    @Override public Set<SearchTier> tiersSupported() {
        return Set.of(SearchTier.NORMAL, SearchTier.EXPERT);
    }

    @Override
    public SearchProviderInstance instantiate(ProviderInstanceConfig cfg) {
        if (cfg == null) throw new IllegalArgumentException("cfg is required");
        if (!ID.equals(cfg.protocolId())) {
            throw new IllegalArgumentException(
                    "ArxivProtocol cannot instantiate config with protocol '"
                            + cfg.protocolId() + "'");
        }
        return new ArxivInstance(cfg, http);
    }

    // ── Instance ─────────────────────────────────────────────────────

    static final class ArxivInstance implements SearchProviderInstance {

        private static final Duration TIMEOUT = Duration.ofSeconds(20);
        private static final int DEFAULT_NUM = 5;
        private static final int MAX_NUM = 25;
        private static final String ATOM_NS = "http://www.w3.org/2005/Atom";
        private static final String ARXIV_NS = "http://arxiv.org/schemas/atom";

        private final ProviderInstanceConfig cfg;
        private final SimpleHttpClient http;

        ArxivInstance(ProviderInstanceConfig cfg, SimpleHttpClient http) {
            this.cfg = cfg;
            this.http = http;
        }

        @Override public String id() { return cfg.instanceId(); }
        @Override public String displayName() { return "arXiv (" + cfg.instanceId() + ")"; }
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
            return "no quota meter (free arXiv API)";
        }

        @Override
        public String promptHint() {
            return "arXiv preprint server. Indexed content: STEM "
                    + "preprints — physics (all branches), mathematics, "
                    + "computer science (including AI / ML), statistics, "
                    + "quantitative biology, quantitative finance, "
                    + "electrical engineering & systems science, "
                    + "economics. Per-paper title, authors, abstract, "
                    + "primary category tag, PDF link. Best for: "
                    + "cutting-edge STEM research before journal "
                    + "publication, theoretical CS / ML papers, math / "
                    + "physics / quant topics. Abstracts come inline "
                    + "with the result.";
        }

        @Override
        public SearchResult search(SearchRequest req, SearchScope scope) {
            if (req.modality() != SearchModality.ACADEMIC) {
                return softFailure(req, "modality " + req.modality()
                        + " not supported by arXiv '" + cfg.instanceId() + "'");
            }
            int num = clampNum(req.maxResults());
            String url = SimpleHttpClient.buildQuery(
                    URI.create(baseUrl() + "/query"),
                    SimpleHttpClient.mapOf(
                            "search_query", "all:" + req.query(),
                            "max_results", String.valueOf(num),
                            "sortBy", "relevance"));
            Response response;
            try {
                response = http.get(URI.create(url), USER_AGENT, TIMEOUT);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(
                        "Interrupted while calling arXiv '" + cfg.instanceId() + "'");
            } catch (Exception e) {
                throw new RuntimeException(
                        "arXiv '" + cfg.instanceId() + "' call failed: " + e.getMessage(), e);
            }
            if (response.statusCode() != 200) {
                throw new RuntimeException("arXiv '" + cfg.instanceId()
                        + "' returned HTTP " + response.statusCode());
            }
            List<SearchHit> hits = parseHits(response.body());
            return new SearchResult(
                    req.query(), req.modality(), cfg.instanceId(), req.tier(),
                    hits, hits.size(), 0, null, null, Map.of());
        }

        List<SearchHit> parseHits(String xml) {
            try {
                Document doc = newSafeBuilder().parse(
                        new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
                NodeList entries = doc.getElementsByTagNameNS(ATOM_NS, "entry");
                List<SearchHit> out = new ArrayList<>();
                for (int i = 0; i < entries.getLength(); i++) {
                    Element entry = (Element) entries.item(i);
                    String title = normaliseText(textOf(entry, ATOM_NS, "title"));
                    if (StringUtils.isBlank(title)) continue;
                    String url = pickArxivUrl(entry);
                    if (StringUtils.isBlank(url)) continue;
                    String abstractText = normaliseText(textOf(entry, ATOM_NS, "summary"));
                    String published = textOf(entry, ATOM_NS, "published");
                    int year = parseYear(published);
                    String authors = collectAuthors(entry);
                    String primaryCategory = primaryCategoryTerm(entry);
                    String pdfUrl = pickPdfUrl(entry);

                    Map<String, Object> extras = new LinkedHashMap<>();
                    if (!StringUtils.isBlank(authors)) extras.put("authors", authors);
                    if (year > 0) extras.put("publicationYear", year);
                    if (!StringUtils.isBlank(primaryCategory)) {
                        extras.put("primaryCategory", primaryCategory);
                    }
                    if (!StringUtils.isBlank(pdfUrl)) extras.put("pdfUrl", pdfUrl);

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
                            composeSnippet(authors, year, primaryCategory),
                            "arXiv",
                            SearchModality.ACADEMIC, content, extras));
                }
                return out;
            } catch (Exception e) {
                log.warn("arXiv '{}': parseHits failed: {}", cfg.instanceId(), e.toString());
                return List.of();
            }
        }

        // ── XML helpers ──────────────────────────────────────────────

        private static DocumentBuilder newSafeBuilder() throws Exception {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(
                    "http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(
                    "http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature(
                    "http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd",
                    false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder();
        }

        private static String textOf(Element parent, String ns, String localName) {
            NodeList list = parent.getElementsByTagNameNS(ns, localName);
            if (list.getLength() == 0) return "";
            Node first = list.item(0);
            return first.getTextContent() == null ? "" : first.getTextContent();
        }

        private static String pickArxivUrl(Element entry) {
            // Two links per entry: rel="alternate" (HTML), rel="related" (PDF).
            NodeList links = entry.getElementsByTagNameNS(ATOM_NS, "link");
            String pdf = null;
            for (int i = 0; i < links.getLength(); i++) {
                Element link = (Element) links.item(i);
                String rel = link.getAttribute("rel");
                String href = link.getAttribute("href");
                String type = link.getAttribute("type");
                if ("alternate".equals(rel) && !StringUtils.isBlank(href)) return href;
                if ("application/pdf".equals(type) && !StringUtils.isBlank(href)) pdf = href;
            }
            // No HTML link → fall back to the abs URL via id.
            String id = textOf(entry, ATOM_NS, "id");
            if (!StringUtils.isBlank(id)) return id;
            return pdf == null ? "" : pdf;
        }

        private static String pickPdfUrl(Element entry) {
            NodeList links = entry.getElementsByTagNameNS(ATOM_NS, "link");
            for (int i = 0; i < links.getLength(); i++) {
                Element link = (Element) links.item(i);
                String type = link.getAttribute("type");
                if ("application/pdf".equals(type)) {
                    String href = link.getAttribute("href");
                    if (!StringUtils.isBlank(href)) return href;
                }
            }
            return "";
        }

        private static String collectAuthors(Element entry) {
            NodeList authors = entry.getElementsByTagNameNS(ATOM_NS, "author");
            StringBuilder sb = new StringBuilder();
            int kept = 0;
            for (int i = 0; i < authors.getLength(); i++) {
                if (kept >= 3) {
                    sb.append(", et al.");
                    break;
                }
                Element author = (Element) authors.item(i);
                String name = textOf(author, ATOM_NS, "name").trim();
                if (StringUtils.isBlank(name)) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(name);
                kept++;
            }
            return sb.toString();
        }

        private static String primaryCategoryTerm(Element entry) {
            NodeList nodes = entry.getElementsByTagNameNS(ARXIV_NS, "primary_category");
            if (nodes.getLength() == 0) return "";
            Element first = (Element) nodes.item(0);
            return first.getAttribute("term");
        }

        private static String composeSnippet(String authors, int year, String category) {
            StringBuilder sb = new StringBuilder();
            if (!StringUtils.isBlank(authors)) sb.append(authors);
            if (year > 0) {
                if (sb.length() > 0) sb.append(" — ");
                sb.append(year);
            }
            if (!StringUtils.isBlank(category)) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(category);
            }
            return sb.length() == 0 ? null : sb.toString();
        }

        private static int parseYear(String iso) {
            if (StringUtils.isBlank(iso) || iso.length() < 4) return 0;
            try {
                return Integer.parseInt(iso.substring(0, 4));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private static String normaliseText(String s) {
            if (s == null) return "";
            return s.replaceAll("\\s+", " ").trim();
        }

        private String baseUrl() {
            String base = cfg.baseUrl();
            if (StringUtils.isBlank(base)) base = "https://export.arxiv.org/api";
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
