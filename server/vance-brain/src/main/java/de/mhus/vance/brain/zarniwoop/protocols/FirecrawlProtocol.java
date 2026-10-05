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
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
 * Firecrawl live web search over the plain REST API
 * ({@code POST https://api.firecrawl.dev/v2/search}).
 *
 * <p>Wire choice: REST, not the hosted MCP server — the keyless tier works on
 * the REST endpoint too (verified 2026-10-05), so an MCP round-trip would buy
 * nothing but an agent tool surface with {@code agent_hints} prose in the
 * payload. See {@code planning/zarniwoop-exa-firecrawl-searxng.md} §2.
 *
 * <p><b>The key is optional.</b> Without one the call runs on Firecrawl's
 * keyless free tier (search + scrape + parse only, per-IP rate limits that
 * are not published, no SLA — the tier was relaunched keyless on 2026-08-28
 * after years of requiring accounts). That makes {@code firecrawl-keyless}
 * the right shape for the shipped fallback chain and the wrong shape for a
 * contracted default; {@link #statusText} says which one an instance is.
 *
 * <p>Quota semantics as for Exa: 402 ("Payment Required: Insufficient
 * credits") is a terminating state →
 * {@link de.mhus.vance.toolpack.research.SearchQuotaExceededException};
 * 429 is pressure → hard failure with {@code Retry-After}. With a key,
 * {@link #currentQuota} reads {@code GET /v2/team/credit-usage} so the
 * dispatcher's proactive zero-quota gate can catch a spent plan before the
 * first failing call.
 */
@Component
@Slf4j
public class FirecrawlProtocol implements SearchProtocol {

    public static final String ID = "firecrawl";
    private static final String USER_AGENT = "Vance-Zarniwoop/0.1 (+https://github.com/mhus/vance)";
    private static final String DEFAULT_BASE_URL = "https://api.firecrawl.dev";

    private final ObjectMapper objectMapper;
    private final JsonHttpClient http;

    @Autowired
    public FirecrawlProtocol(ObjectMapper objectMapper) {
        this(objectMapper, new JsonHttpClient.JdkJsonHttpClient());
    }

    FirecrawlProtocol(ObjectMapper objectMapper, JsonHttpClient http) {
        this.objectMapper = objectMapper;
        this.http = http;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Firecrawl";
    }

    @Override
    public Set<SearchModality> modalitiesSupported() {
        return Set.of(SearchModality.WEB, SearchModality.NEWS, SearchModality.IMAGE);
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
                    "FirecrawlProtocol cannot instantiate config with protocol '" + cfg.protocolId() + "'");
        }
        return new FirecrawlInstance(cfg, objectMapper, http);
    }

    // ── Instance ─────────────────────────────────────────────────────

    static final class FirecrawlInstance implements SearchProviderInstance {

        private static final Duration TIMEOUT = Duration.ofSeconds(20);
        private static final int DEFAULT_NUM = 10;
        private static final int MAX_NUM = 50;

        private final ProviderInstanceConfig cfg;
        private final ObjectMapper objectMapper;
        private final JsonHttpClient http;

        FirecrawlInstance(ProviderInstanceConfig cfg, ObjectMapper objectMapper, JsonHttpClient http) {
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
            return "Firecrawl (" + cfg.instanceId() + ")";
        }

        @Override
        public Set<SearchModality> modalities() {
            return Set.of(SearchModality.WEB, SearchModality.NEWS, SearchModality.IMAGE);
        }

        @Override
        public Set<SearchDomain> domains() {
            return Set.of(SearchDomain.GENERAL, SearchDomain.NEWS);
        }

        @Override
        public Set<SearchTier> tiers() {
            return Set.of(SearchTier.NORMAL, SearchTier.EXPERT);
        }

        @Override
        public ProviderAvailability availability(SearchScope scope) {
            // Keyless is a supported mode here (unlike Exa): the instance is
            // usable either way, it just spends a different budget.
            return ProviderAvailability.READY;
        }

        @Override
        public Optional<QuotaStatus> currentQuota(SearchScope scope) {
            String credential = cfg.credential();
            if (StringUtils.isBlank(credential)) {
                // The keyless tier has no account to ask about; its limit
                // surfaces as 429 with Retry-After like any rate limit.
                return Optional.empty();
            }
            try {
                JsonResponse response =
                        http.get(URI.create(baseUrl() + "/v2/team/credit-usage"), authHeaders(credential), TIMEOUT);
                if (response.statusCode() != 200) {
                    return Optional.empty();
                }
                JsonNode data = objectMapper.readTree(response.body()).path("data");
                if (!data.isObject()) {
                    return Optional.empty();
                }
                JsonNode remaining = data.path("remainingCredits");
                if (!remaining.isNumber()) {
                    return Optional.empty();
                }
                JsonNode plan = data.path("planCredits");
                JsonNode periodEnd = data.path("billingPeriodEnd");
                return Optional.of(new QuotaStatus(
                        remaining.asLong(),
                        plan.isNumber() ? plan.asLong() : null,
                        parseInstant(periodEnd.asText("")),
                        null));
            } catch (Exception e) {
                log.debug("Firecrawl '{}': credit-usage probe failed: {}", cfg.instanceId(), e.toString());
                return Optional.empty();
            }
        }

        @Override
        public String statusText(SearchScope scope) {
            return StringUtils.isBlank(cfg.credential())
                    ? "keyless (per-IP free tier, limits unpublished)"
                    : "api key configured (plan credits apply)";
        }

        @Override
        public String promptHint() {
            return "Firecrawl live web search — ranked web results with the "
                    + "cleaned page excerpt in one call. Covers web, news and "
                    + "images (`sources` per modality). Best for: fresh web "
                    + "pages where an excerpt beats a bare link. Expert "
                    + "filters: `includeDomains`, `excludeDomains`, "
                    + "`categories`, `tbs` (time filter as Google's tbs "
                    + "value). Each call spends plan credits when a key is "
                    + "configured; without a key it runs on Firecrawl's "
                    + "keyless free tier (per-IP, undocumented limits) — so "
                    + "it sits behind credited sources in the fallback chain. "
                    + "When credits run out the source steps aside and the "
                    + "next candidate answers.";
        }

        @Override
        public SearchResult search(SearchRequest req, SearchScope scope) {
            String source = sourceFor(req.modality());
            if (source == null) {
                return SearchResult.softFailure(
                        req, cfg.instanceId(), "Firecrawl does not serve modality " + req.modality());
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", req.query());
            body.put("limit", clampNum(req.maxResults()));
            body.put("sources", List.of(source));
            List<String> include = strings(req, "includeDomains", "site", "domain");
            if (!include.isEmpty()) {
                body.put("includeDomains", include);
            }
            List<String> exclude = strings(req, "excludeDomains");
            if (!exclude.isEmpty()) {
                body.put("excludeDomains", exclude);
            }
            String tbs = expertString(req, "tbs", "");
            if (!tbs.isEmpty()) {
                body.put("tbs", tbs);
            }
            String categories = expertString(req, "categories", "");
            if (!categories.isEmpty()) {
                body.put("categories", List.of(categories));
            }

            String url = baseUrl() + "/v2/search";
            JsonResponse response;
            try {
                response = http.post(
                        URI.create(url), authHeaders(cfg.credential()), objectMapper.writeValueAsString(body), TIMEOUT);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while calling Firecrawl '" + cfg.instanceId() + "'", ie);
            } catch (Exception e) {
                throw new RuntimeException("Firecrawl '" + cfg.instanceId() + "' call failed: " + e.getMessage(), e);
            }
            if (response.statusCode() != 200) {
                throw ProtocolErrors.httpFailure(
                        objectMapper, "Firecrawl '" + cfg.instanceId() + "' /v2/search", response, null);
            }
            List<SearchHit> hits = parseHits(response.body(), source, req.modality());
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

        /** Firecrawl {@code sources} entry for a modality. */
        private static String sourceFor(SearchModality modality) {
            return switch (modality) {
                case WEB -> "web";
                case NEWS -> "news";
                case IMAGE -> "images";
                default -> null;
            };
        }

        List<SearchHit> parseHits(String json, String source, SearchModality modality) {
            JsonNode root;
            try {
                root = objectMapper.readTree(json);
            } catch (RuntimeException e) {
                log.warn("Firecrawl '{}': response is not JSON: {}", cfg.instanceId(), e.toString());
                return List.of();
            }
            JsonNode arr = root.path("data").path(source);
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

            Map<String, Object> extras = new LinkedHashMap<>();
            putIfPresent(extras, "imageUrl", item.path("imageUrl").asText(""));
            putIfPresent(extras, "publishedDate", item.path("date").asText(""));
            putIfPresent(extras, "category", item.path("category").asText(""));
            JsonNode position = item.path("position");
            if (position.isNumber()) {
                extras.put("position", position.asInt());
            }
            JsonNode width = item.path("imageWidth");
            if (width.isNumber()) {
                extras.put("imageWidth", width.asInt());
            }
            JsonNode height = item.path("imageHeight");
            if (height.isNumber()) {
                extras.put("imageHeight", height.asInt());
            }

            // The provider's own text: `description` for web results,
            // `snippet` for news (markdown-ish excerpts), nothing for images.
            String text = item.path("description").asText("");
            if (StringUtils.isBlank(text)) {
                text = item.path("snippet").asText("");
            }
            ContentReference content = null;
            if (!StringUtils.isBlank(text)) {
                content = new ContentReference(
                        cfg.instanceId() + ":hit:" + java.util.UUID.randomUUID(),
                        "text/plain",
                        text.length(),
                        ContentInline.EMBED_TEXT,
                        text,
                        null);
            }

            return new SearchHit(
                    title, url, StringUtils.isBlank(text) ? null : text, "Firecrawl", modality, content, extras);
        }

        private static void putIfPresent(Map<String, Object> extras, String key, String value) {
            if (!StringUtils.isBlank(value)) {
                extras.put(key, value);
            }
        }

        /**
         * Firecrawl speaks {@code Authorization: Bearer fc-…}. Keyless
         * instances send no auth header at all — that is a supported mode,
         * not a missing configuration.
         */
        private static Map<String, String> authHeaders(String credential) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", USER_AGENT);
            if (!StringUtils.isBlank(credential)) {
                headers.put("Authorization", "Bearer " + credential.trim());
            }
            return headers;
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

        private static @Nullable Instant parseInstant(String text) {
            if (StringUtils.isBlank(text)) {
                return null;
            }
            try {
                return java.time.OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                        .toInstant();
            } catch (DateTimeParseException e) {
                try {
                    return Instant.parse(text);
                } catch (DateTimeParseException e2) {
                    return null;
                }
            }
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
    }
}
