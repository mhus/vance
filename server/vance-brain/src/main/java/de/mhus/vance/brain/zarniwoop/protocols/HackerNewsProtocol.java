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
        public String promptHint() {
            return "HackerNews search via Algolia. Matches against "
                    + "BOTH story titles AND comment bodies in a single "
                    + "call. Indexed scope: software engineering, "
                    + "programming languages, dev tooling, AI / ML, "
                    + "computing infrastructure, security, startups, "
                    + "SaaS, venture capital, and the tech community's "
                    + "discussion threads around them. Best for: "
                    + "questions about software / the tech industry "
                    + "where the user wants practitioner perspectives, "
                    + "opinions from working engineers, or current "
                    + "discussion around a dev tool, framework, or "
                    + "company. Query style: topic words as they would "
                    + "appear in discussion — tool names (`Rust`, "
                    + "`Kubernetes`), specific technologies "
                    + "(`server components`, `WebGPU`), CVE ids, but "
                    + "also broader discussion terms work because the "
                    + "comment text is in scope (`supply chain "
                    + "attacks`, `AI agents`, `vibe coding`). Each "
                    + "hit's `hnItemKind` extra says whether it was a "
                    + "story (title-match) or a comment (body-match); "
                    + "comment hits carry the parent-story title + "
                    + "url + a snippet of the comment text.";
        }

        @Override
        public SearchResult search(SearchRequest req, SearchScope scope) {
            int num = clampNum(req.maxResults());
            // tags=story,comment matches both Story titles and the
            // body text of comments — the second piece is where actual
            // community discussion lives, and a query like "supply
            // chain attacks" hits it much more reliably than story
            // titles alone.
            String url = SimpleHttpClient.buildQuery(
                    URI.create(baseUrl() + "/search"),
                    SimpleHttpClient.mapOf(
                            "query", req.query(),
                            "tags", "(story,comment)",
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

        /**
         * Maximum chars to surface as snippet from a comment body.
         * Algolia HN returns the full comment text — long ones blow
         * up the LLM context if we forward them verbatim.
         */
        static final int COMMENT_SNIPPET_MAX = 320;

        List<SearchHit> parseHits(String json, SearchModality modality) {
            try {
                JsonNode root = objectMapper.readTree(json);
                JsonNode arr = root.path("hits");
                List<SearchHit> out = new ArrayList<>();
                if (!arr.isArray()) return out;
                for (JsonNode item : arr) {
                    SearchHit hit = parseOneHit(item, modality);
                    if (hit != null) out.add(hit);
                }
                return out;
            } catch (Exception e) {
                log.warn("HN '{}': parseHits failed: {}", cfg.instanceId(), e.toString());
                return List.of();
            }
        }

        /**
         * Single Algolia hit → SearchHit. Algolia's HN endpoint
         * returns story items and comment items in the same array,
         * distinguishable by which fields they carry:
         * <ul>
         *   <li>Story: {@code title}, {@code url} (optional), {@code points},
         *     {@code num_comments}.</li>
         *   <li>Comment: {@code comment_text}, {@code story_id},
         *     {@code story_title}, {@code story_url} (optional).</li>
         * </ul>
         * Both expose {@code objectID}, {@code author}, {@code created_at}.
         */
        private SearchHit parseOneHit(JsonNode item, SearchModality modality) {
            String objectId = item.path("objectID").asText("");
            if (StringUtils.isBlank(objectId)) return null;

            String storyTitle = item.path("title").asText("");
            String commentText = item.path("comment_text").asText("");
            boolean isComment = !StringUtils.isBlank(commentText);

            String title;
            String url;
            String snippet = null;
            String itemKind;

            if (isComment) {
                title = item.path("story_title").asText("");
                if (StringUtils.isBlank(title)) title = "(HN comment)";
                url = item.path("story_url").asText("");
                if (StringUtils.isBlank(url)) {
                    // story_url is missing for Ask-HN / Show-HN parents
                    // — link to the comment item directly.
                    url = "https://news.ycombinator.com/item?id=" + objectId;
                }
                snippet = stripHtml(commentText);
                if (snippet.length() > COMMENT_SNIPPET_MAX) {
                    snippet = snippet.substring(0, COMMENT_SNIPPET_MAX) + "…";
                }
                itemKind = "comment";
            } else {
                title = storyTitle;
                if (StringUtils.isBlank(title)) return null;
                url = item.path("url").asText("");
                if (StringUtils.isBlank(url)) {
                    // Ask-HN / Show-HN / job posts without an external URL
                    // — link to the HN item page.
                    url = "https://news.ycombinator.com/item?id=" + objectId;
                }
                itemKind = "story";
            }

            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("hnItemKind", itemKind);
            String author = item.path("author").asText("");
            if (!StringUtils.isBlank(author)) extras.put("author", author);
            int points = item.path("points").asInt(0);
            if (points > 0) extras.put("points", points);
            int comments = item.path("num_comments").asInt(0);
            if (comments > 0) extras.put("comments", comments);
            String createdAt = item.path("created_at").asText("");
            if (!StringUtils.isBlank(createdAt)) extras.put("createdAt", createdAt);
            extras.put("hnDiscussion",
                    "https://news.ycombinator.com/item?id=" + objectId);

            return new SearchHit(title, url, snippet, "HackerNews", modality, null, extras);
        }

        /**
         * Algolia returns comment text with HTML entities and a few
         * inline tags ({@code <p>}, {@code <a>}). Strip the tags so
         * the snippet is plain text the LLM can use as context.
         */
        static String stripHtml(String s) {
            if (s == null) return "";
            String stripped = s.replaceAll("<[^>]+>", " ");
            return stripped.replaceAll("\\s+", " ").trim();
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

    }
}
