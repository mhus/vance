package de.mhus.vance.brain.zarniwoop.protocols;

import de.mhus.vance.brain.tools.web.ImageValidatorService;
import de.mhus.vance.brain.tools.web.YouTubeValidatorService;
import de.mhus.vance.brain.zarniwoop.protocols.SerperHttpClient.SerperResponse;
import de.mhus.vance.brain.zarniwoop.protocols.SerperPdfHeadProbe.Verdict;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.toolpack.research.ProviderAvailability;
import de.mhus.vance.toolpack.research.ProviderInstanceConfig;
import de.mhus.vance.toolpack.research.QuotaStatus;
import de.mhus.vance.toolpack.research.SearchDomain;
import de.mhus.vance.toolpack.research.SearchHit;
import de.mhus.vance.toolpack.research.SearchModality;
import de.mhus.vance.toolpack.research.SearchProviderInstance;
import de.mhus.vance.toolpack.research.SearchRequest;
import de.mhus.vance.toolpack.research.SearchResult;
import de.mhus.vance.toolpack.research.SearchScope;
import de.mhus.vance.toolpack.research.SearchTier;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One configured Serper-compatible endpoint. Built by
 * {@link SerperProtocol#instantiate} per project; not a Spring bean.
 *
 * <p>WEB-only in v2a — IMAGE/VIDEO/PDF follow in 2b with the original
 * {@code ImageValidatorService}/{@code YouTubeValidatorService}/HEAD
 * probe re-used as collaborators.
 *
 * <p>API-key resolution falls back to the legacy
 * {@code web.serper.apiKey} setting when the
 * {@code research.endpoint.<id>.apiKey} key is empty — handles the
 * transition window from §10.7 of the planning document. A
 * deprecation warn is logged the first time the legacy key takes
 * effect.
 */
@Slf4j
class SerperInstance implements SearchProviderInstance {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final int DEFAULT_NUM = 5;
    private static final int MAX_NUM = 10;

    /** Legacy setting key — read as fallback during the migration window. */
    static final String LEGACY_API_KEY_SETTING = "web.serper.apiKey";

    private final ProviderInstanceConfig cfg;
    private final SettingService settings;
    private final ObjectMapper objectMapper;
    private final SerperHttpClient http;
    private final ImageValidatorService imageValidator;
    private final YouTubeValidatorService youtubeValidator;
    private final SerperPdfHeadProbe pdfHeadProbe;

    /** Logs the legacy-fallback warning only once per instance lifetime. */
    private final AtomicBoolean legacyFallbackLogged = new AtomicBoolean(false);

    SerperInstance(ProviderInstanceConfig cfg,
                   SettingService settings,
                   ObjectMapper objectMapper,
                   SerperHttpClient http,
                   ImageValidatorService imageValidator,
                   YouTubeValidatorService youtubeValidator,
                   SerperPdfHeadProbe pdfHeadProbe) {
        this.cfg = cfg;
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.http = http;
        this.imageValidator = imageValidator;
        this.youtubeValidator = youtubeValidator;
        this.pdfHeadProbe = pdfHeadProbe;
    }

    @Override
    public String id() {
        return cfg.instanceId();
    }

    @Override
    public String displayName() {
        return "Serper (" + cfg.instanceId() + ")";
    }

    @Override
    public Set<SearchModality> modalities() {
        return Set.of(
                SearchModality.WEB,
                SearchModality.IMAGE,
                SearchModality.VIDEO,
                SearchModality.PDF);
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
        String key = resolveApiKey(scope);
        return StringUtils.isBlank(key)
                ? ProviderAvailability.NO_CREDENTIALS
                : ProviderAvailability.READY;
    }

    @Override
    public String statusText(SearchScope scope) {
        if (StringUtils.isBlank(resolveApiKey(scope))) {
            return "no API key configured";
        }
        Optional<QuotaStatus> q = currentQuota(scope);
        if (q.isEmpty()) return null;
        QuotaStatus s = q.get();
        StringBuilder out = new StringBuilder();
        out.append(s.remaining()).append(" credits remaining");
        if (s.limit() != null) out.append(" / ").append(s.limit());
        if (s.resetsAt() != null) out.append(" (resets ").append(s.resetsAt()).append(")");
        return out.toString();
    }

    @Override
    public Optional<QuotaStatus> currentQuota(SearchScope scope) {
        String apiKey = resolveApiKey(scope);
        if (StringUtils.isBlank(apiKey)) return Optional.empty();
        try {
            SerperResponse response = http.get(
                    URI.create(baseUrl() + "/account"), apiKey, REQUEST_TIMEOUT);
            if (response.statusCode() != 200) {
                log.debug("Serper /account for '{}' returned status {}",
                        cfg.instanceId(), response.statusCode());
                return Optional.empty();
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (!root.has("balance")) return Optional.empty();
            long balance = root.path("balance").asLong(0L);
            return Optional.of(new QuotaStatus(balance, null, null, null));
        } catch (Exception e) {
            log.debug("Serper /account for '{}' failed: {}", cfg.instanceId(), e.toString());
            return Optional.empty();
        }
    }

    @Override
    public SearchResult search(SearchRequest req, SearchScope scope) {
        String apiKey = resolveApiKey(scope);
        if (StringUtils.isBlank(apiKey)) {
            return softFailure(req, "Serper API key not configured for endpoint '"
                    + cfg.instanceId() + "'");
        }
        return switch (req.modality()) {
            case WEB -> doWebSearch(req, scope, apiKey);
            case IMAGE -> doImageSearch(req, scope, apiKey);
            case VIDEO -> doVideoSearch(req, scope, apiKey);
            case PDF -> doPdfSearch(req, scope, apiKey);
            default -> softFailure(req, "modality " + req.modality()
                    + " not supported by Serper endpoint '" + cfg.instanceId() + "'");
        };
    }

    // ── WEB ───────────────────────────────────────────────────────────

    private SearchResult doWebSearch(SearchRequest req, SearchScope scope, String apiKey) {
        SerperResponse response = postSerper("/search",
                Map.of("q", req.query(), "num", clampNum(req.maxResults())),
                apiKey);
        List<SearchHit> hits = parseHits(response.body(), SearchModality.WEB);
        return successResult(req, hits, 0, response.headers(), null);
    }

    // ── IMAGE ─────────────────────────────────────────────────────────

    private SearchResult doImageSearch(SearchRequest req, SearchScope scope, String apiKey) {
        SerperResponse response = postSerper("/images",
                Map.of("q", req.query(), "num", clampNum(req.maxResults())),
                apiKey);
        List<RawImage> raw = parseImages(response.body());
        if (raw.isEmpty()) {
            return successResult(req, List.of(), 0, response.headers(), null);
        }
        List<String> urls = new ArrayList<>(raw.size());
        for (RawImage r : raw) urls.add(r.imageUrl);
        List<ImageValidatorService.ValidationResult> verdicts = imageValidator.validate(
                urls, scope.tenantId(), scope.projectId(), scope.processId());
        Map<String, ImageValidatorService.ValidationResult> byUrl = new HashMap<>();
        for (ImageValidatorService.ValidationResult v : verdicts) byUrl.put(v.getUrl(), v);

        List<SearchHit> hits = new ArrayList<>();
        int dropped = 0;
        for (RawImage r : raw) {
            ImageValidatorService.ValidationResult v = byUrl.get(r.imageUrl);
            if (v == null || !v.isOk()) {
                dropped++;
                continue;
            }
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("imageUrl", r.imageUrl);
            if (!StringUtils.isBlank(r.thumbnailUrl)) extras.put("thumbnailUrl", r.thumbnailUrl);
            if (v.getContentType() != null) extras.put("contentType", v.getContentType());
            hits.add(new SearchHit(
                    isBlankOr(r.title, "(untitled image)"),
                    isBlankOr(r.sourceLink, r.imageUrl),
                    null,
                    StringUtils.isBlank(r.source) ? null : r.source,
                    SearchModality.IMAGE,
                    null,
                    extras));
        }
        String note = (hits.isEmpty() && dropped > 0)
                ? "All " + dropped + " image URLs failed validation"
                : null;
        return successResult(req, hits, dropped, response.headers(), note);
    }

    // ── VIDEO ─────────────────────────────────────────────────────────

    private SearchResult doVideoSearch(SearchRequest req, SearchScope scope, String apiKey) {
        SerperResponse response = postSerper("/videos",
                Map.of("q", req.query(), "num", clampNum(req.maxResults())),
                apiKey);
        List<RawVideo> raw = parseVideos(response.body());
        List<SearchHit> hits = new ArrayList<>();
        int dropped = 0;
        for (RawVideo r : raw) {
            String videoId = YouTubeValidatorService.extractVideoId(r.videoLink);
            if (videoId == null || !youtubeValidator.isEmbeddable(videoId)) {
                dropped++;
                continue;
            }
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("videoId", videoId);
            extras.put("videoUrl", "https://youtu.be/" + videoId);
            if (!StringUtils.isBlank(r.thumbnailUrl)) extras.put("thumbnailUrl", r.thumbnailUrl);
            if (!StringUtils.isBlank(r.duration)) extras.put("duration", r.duration);
            if (!StringUtils.isBlank(r.channel)) extras.put("channel", r.channel);
            if (!StringUtils.isBlank(r.date)) extras.put("date", r.date);
            extras.put("embedFence",
                    "```youtube\nhttps://youtu.be/" + videoId + "\n```");
            hits.add(new SearchHit(
                    isBlankOr(r.title, "(untitled video)"),
                    "https://youtu.be/" + videoId,
                    null,
                    null,
                    SearchModality.VIDEO,
                    null,
                    extras));
        }
        String note = (hits.isEmpty() && dropped > 0)
                ? "All " + dropped + " video URLs failed embeddability check"
                : null;
        return successResult(req, hits, dropped, response.headers(), note);
    }

    // ── PDF ───────────────────────────────────────────────────────────

    private SearchResult doPdfSearch(SearchRequest req, SearchScope scope, String apiKey) {
        // Serper has no /pdfs endpoint — we append filetype:pdf to a /search call.
        SerperResponse response = postSerper("/search",
                Map.of("q", req.query() + " filetype:pdf",
                        "num", clampNum(req.maxResults())),
                apiKey);
        List<RawPdf> raw = parsePdfRows(response.body());
        List<SearchHit> hits = new ArrayList<>();
        int dropped = 0;
        for (RawPdf r : raw) {
            Verdict v;
            try {
                v = pdfHeadProbe.head(URI.create(r.url), Duration.ofSeconds(2));
            } catch (Exception e) {
                v = Verdict.fail("head_failed: " + e.getClass().getSimpleName());
            }
            if (!v.ok()) {
                dropped++;
                continue;
            }
            Map<String, Object> extras = new LinkedHashMap<>();
            if (v.contentLength() > 0) extras.put("sizeBytes", v.contentLength());
            if (v.contentType() != null) extras.put("contentType", v.contentType());
            hits.add(new SearchHit(
                    isBlankOr(r.title, "(untitled PDF)"),
                    v.finalUrl() == null ? r.url : v.finalUrl(),
                    StringUtils.isBlank(r.snippet) ? null : r.snippet,
                    StringUtils.isBlank(r.source) ? null : r.source,
                    SearchModality.PDF,
                    null,
                    extras));
        }
        String note = (hits.isEmpty() && dropped > 0)
                ? "All " + dropped + " PDF URLs failed the content-type check"
                : null;
        return successResult(req, hits, dropped, response.headers(), note);
    }

    // ── shared upstream call ─────────────────────────────────────────

    private SerperResponse postSerper(String path, Map<String, Object> body, String apiKey) {
        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Serper '" + cfg.instanceId() + "' body serialisation failed: " + e.getMessage(), e);
        }
        SerperResponse response;
        try {
            response = http.post(URI.create(baseUrl() + path), apiKey, jsonBody, REQUEST_TIMEOUT);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while calling Serper '" + cfg.instanceId() + "'");
        } catch (Exception e) {
            throw new RuntimeException(
                    "Serper '" + cfg.instanceId() + "' call failed: " + e.getMessage(), e);
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException("Serper '" + cfg.instanceId() + "' " + path
                    + " returned HTTP " + response.statusCode()
                    + ": " + truncate(response.body(), 200));
        }
        return response;
    }

    private SearchResult successResult(SearchRequest req, List<SearchHit> hits,
                                       int dropped, Map<String, String> headers,
                                       @org.jspecify.annotations.Nullable String note) {
        return new SearchResult(
                req.query(),
                req.modality(),
                cfg.instanceId(),
                req.tier(),
                hits,
                hits.size(),
                dropped,
                note,
                null,
                headers == null ? Map.of() : headers);
    }

    private static String isBlankOr(String value, String fallback) {
        return StringUtils.isBlank(value) ? fallback : value;
    }

    // ── parsing ───────────────────────────────────────────────────────

    private List<RawImage> parseImages(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode arr = root.path("images");
            List<RawImage> out = new ArrayList<>();
            if (!arr.isArray()) return out;
            for (JsonNode item : arr) {
                String imageUrl = item.path("imageUrl").asText("");
                if (StringUtils.isBlank(imageUrl)) continue;
                RawImage r = new RawImage();
                r.title = item.path("title").asText("");
                r.imageUrl = imageUrl;
                r.thumbnailUrl = item.path("thumbnailUrl").asText("");
                r.source = item.path("source").asText("");
                r.sourceLink = item.path("link").asText("");
                out.add(r);
            }
            return out;
        } catch (Exception e) {
            log.warn("Serper '{}': parseImages failed: {}", cfg.instanceId(), e.toString());
            return List.of();
        }
    }

    private List<RawVideo> parseVideos(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode arr = root.path("videos");
            List<RawVideo> out = new ArrayList<>();
            if (!arr.isArray()) return out;
            for (JsonNode item : arr) {
                String link = item.path("link").asText("");
                if (StringUtils.isBlank(link)) continue;
                RawVideo r = new RawVideo();
                r.title = item.path("title").asText("");
                r.videoLink = link;
                r.thumbnailUrl = item.path("imageUrl").asText("");
                r.duration = item.path("duration").asText("");
                r.channel = item.path("channel").asText("");
                r.date = item.path("date").asText("");
                out.add(r);
            }
            return out;
        } catch (Exception e) {
            log.warn("Serper '{}': parseVideos failed: {}", cfg.instanceId(), e.toString());
            return List.of();
        }
    }

    private List<RawPdf> parsePdfRows(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode arr = root.path("organic");
            List<RawPdf> out = new ArrayList<>();
            if (!arr.isArray()) return out;
            for (JsonNode item : arr) {
                String link = item.path("link").asText("");
                if (StringUtils.isBlank(link)) continue;
                RawPdf r = new RawPdf();
                r.title = item.path("title").asText("");
                r.url = link;
                r.source = item.path("source").asText("");
                r.snippet = item.path("snippet").asText("");
                out.add(r);
            }
            return out;
        } catch (Exception e) {
            log.warn("Serper '{}': parsePdfRows failed: {}", cfg.instanceId(), e.toString());
            return List.of();
        }
    }

    private static final class RawImage {
        String title = "";
        String imageUrl = "";
        String thumbnailUrl = "";
        String source = "";
        String sourceLink = "";
    }

    private static final class RawVideo {
        String title = "";
        String videoLink = "";
        String thumbnailUrl = "";
        String duration = "";
        String channel = "";
        String date = "";
    }

    private static final class RawPdf {
        String title = "";
        String url = "";
        String source = "";
        String snippet = "";
    }

    // ── helpers ───────────────────────────────────────────────────────

    private String baseUrl() {
        String base = cfg.baseUrl();
        if (StringUtils.isBlank(base)) {
            base = "https://google.serper.dev";
        }
        // Strip trailing slash so concatenation with "/search" / "/account" is well-formed.
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** Resolve the API key with legacy-setting fallback. */
    String resolveApiKey(SearchScope scope) {
        String fromInstance = settings.getDecryptedPasswordCascade(
                scope.tenantId(), scope.projectId(), scope.processId(),
                cfg.credentialSettingKey());
        if (!StringUtils.isBlank(fromInstance)) return fromInstance;
        String legacy = settings.getDecryptedPasswordCascade(
                scope.tenantId(), scope.projectId(), scope.processId(),
                LEGACY_API_KEY_SETTING);
        if (!StringUtils.isBlank(legacy)) {
            // Latch keeps the migration warning to one line per
            // instance lifetime — the dispatcher would otherwise emit
            // it on every availability + search pair (6+ lines per
            // research_investigate call).
            if (legacyFallbackLogged.compareAndSet(false, true)) {
                log.warn("Serper endpoint '{}' falling back to legacy setting '{}'. "
                        + "Migrate to '{}' before the next release removes the legacy path.",
                        cfg.instanceId(), LEGACY_API_KEY_SETTING, cfg.credentialSettingKey());
            }
            return legacy;
        }
        return "";
    }

    List<SearchHit> parseHits(String json, SearchModality modality) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode organic = root.path("organic");
            List<SearchHit> rows = new ArrayList<>();
            if (organic.isArray()) {
                for (JsonNode item : organic) {
                    String title = item.path("title").asText("");
                    if (StringUtils.isBlank(title)) continue;
                    String link = item.path("link").asText("");
                    if (StringUtils.isBlank(link)) continue;
                    String snippet = item.path("snippet").asText("");
                    String source = item.path("source").asText("");
                    Map<String, Object> extras = new LinkedHashMap<>();
                    if (item.has("position")) {
                        extras.put("position", item.path("position").asInt());
                    }
                    rows.add(new SearchHit(
                            title,
                            link,
                            StringUtils.isBlank(snippet) ? null : snippet,
                            StringUtils.isBlank(source) ? null : source,
                            modality,
                            null,
                            extras));
                }
            }
            return rows;
        } catch (Exception e) {
            log.warn("Serper '{}': failed to parse response body: {}",
                    cfg.instanceId(), e.toString());
            return List.of();
        }
    }

    static int clampNum(int requested) {
        if (requested < 1) return DEFAULT_NUM;
        if (requested > MAX_NUM) return MAX_NUM;
        return requested;
    }

    private SearchResult softFailure(SearchRequest req, String message) {
        return new SearchResult(
                req.query(),
                req.modality(),
                cfg.instanceId(),
                req.tier(),
                List.of(),
                0,
                0,
                null,
                message,
                Map.of());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    @Override
    public String promptHint() {
        return "Generic Google SERP via Serper.dev. Indexes the open "
                + "web across every topic — news sites, blogs, "
                + "documentation, commercial pages, forums, government "
                + "pages, image search, YouTube video search, and PDFs "
                + "sitting on public servers. Best for: current events, "
                + "mainstream coverage, finding an authoritative "
                + "landing page for a topic, mixed-modality requests "
                + "(image / video / pdf alongside text). Query style: "
                + "natural-language search phrasing — the same words a "
                + "human would type into Google. Google operators "
                + "(`site:`, `filetype:`, `\"…\"`) work and can sharpen "
                + "results when the topic is broad.";
    }

    /** Used by tests to peek at the resolved config. */
    ProviderInstanceConfig config() {
        return cfg;
    }
}
