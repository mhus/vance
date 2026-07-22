package de.mhus.vance.brain.tools.web;

import de.mhus.vance.api.web.LinkPreviewDto;
import de.mhus.vance.shared.net.SsrfGuard;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.web.LinkPreviewCacheDocument;
import de.mhus.vance.shared.web.LinkPreviewCacheRepository;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Server-side OpenGraph proxy. Web-UI calls
 * {@code GET /brain/{tenant}/link-preview?url=&lt;...&gt;} to get a
 * Slack-style preview card for any external link the LLM emitted.
 *
 * <p>Why server-side? CORS. The browser can fetch {@code og:title}
 * metadata only from origins that explicitly opt in via
 * {@code Access-Control-Allow-Origin} — most don't, so a client-only
 * approach would silently fail on roughly half the links. The proxy
 * eats the cross-origin call once per URL and caches the verdict in
 * {@link LinkPreviewCacheDocument} so the next session sees the same
 * preview without another fetch.
 *
 * <p>Cache is tenant-agnostic — OG-tags are public metadata, identical
 * for every tenant that asks. Two tenants requesting the same URL hit
 * the same row.
 *
 * <p>Tuning: {@code web.linkPreview.*} settings via the project
 * cascade — {@code timeoutMs} (default 4000),
 * {@code cacheTtlOkHours} (default 168 = 7 days),
 * {@code cacheTtlFailMinutes} (default 60),
 * {@code maxBodyBytes} (default 256 KiB).
 */
@Service
@Slf4j
public class LinkPreviewService {

    static final String SETTING_TIMEOUT_MS = "web.linkPreview.timeoutMs";
    static final String SETTING_CACHE_TTL_OK_HOURS = "web.linkPreview.cacheTtlOkHours";
    static final String SETTING_CACHE_TTL_FAIL_MINUTES = "web.linkPreview.cacheTtlFailMinutes";
    static final String SETTING_MAX_BODY_BYTES = "web.linkPreview.maxBodyBytes";

    private static final int DEFAULT_TIMEOUT_MS = 4000;
    private static final int DEFAULT_CACHE_TTL_OK_HOURS = 24 * 7;
    private static final int DEFAULT_CACHE_TTL_FAIL_MINUTES = 60;
    private static final int DEFAULT_MAX_BODY_BYTES = 256 * 1024;

    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";

    private final LinkPreviewCacheRepository cache;
    private final SettingService settings;
    private final PreviewHttp http;

    @Autowired
    public LinkPreviewService(LinkPreviewCacheRepository cache, SettingService settings) {
        this(cache, settings, new JdkPreviewHttp());
    }

    /** Test-seam constructor. */
    LinkPreviewService(LinkPreviewCacheRepository cache, SettingService settings, PreviewHttp http) {
        this.cache = cache;
        this.settings = settings;
        this.http = http;
    }

    /**
     * Resolves the preview for {@code url}, hitting cache when fresh.
     * Always returns a DTO — never throws — so the controller can map
     * it to a 200 response regardless of whether the upstream fetch
     * succeeded. The DTO's {@code ok} field tells the UI which card
     * variant to render.
     */
    public LinkPreviewDto preview(
            String url,
            @Nullable String tenantId,
            @Nullable String projectId,
            @Nullable String processId) {
        if (url == null || url.isBlank()) {
            return failedDto("", "blank_url");
        }
        PreviewConfig cfg = configFor(tenantId, projectId, processId);
        try {
            Optional<LinkPreviewCacheDocument> cached = cache.findByUrl(url);
            if (cached.isPresent()) {
                LinkPreviewCacheDocument doc = cached.get();
                Instant exp = doc.getExpireAt();
                if (exp == null || exp.isAfter(Instant.now())) {
                    return fromCache(doc);
                }
            }
            LinkPreviewDto fresh = probe(url, cfg);
            persist(fresh, cfg);
            return fresh;
        } catch (Exception e) {
            log.debug("LinkPreview: unexpected failure for {}: {}", url, e.toString());
            return failedDto(url, "exception: " + e.getClass().getSimpleName());
        }
    }

    private LinkPreviewDto probe(String url, PreviewConfig cfg) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return failedDto(url, "invalid_uri");
        }
        if (uri.getScheme() == null
                || !(uri.getScheme().equalsIgnoreCase("http")
                        || uri.getScheme().equalsIgnoreCase("https"))) {
            return failedDto(url, "scheme_not_http");
        }

        PreviewHttpResponse resp;
        try {
            resp = http.get(uri, Duration.ofMillis(cfg.timeoutMs),
                    BROWSER_USER_AGENT, cfg.maxBodyBytes);
        } catch (Exception e) {
            return failedDto(url, "fetch_failed: " + e.getClass().getSimpleName());
        }

        String finalUrl = resp.finalUri == null ? url : resp.finalUri.toString();
        URI finalUri = resp.finalUri == null ? uri : resp.finalUri;

        if (resp.status >= 400) {
            // 401/403/429 mean the page is up but the host is refusing
            // our specific request (Cloudflare bot challenge, login
            // wall, rate limit). The link still works for the user in
            // a real browser — surface it as "reachable but preview
            // restricted" with the hostname populated, so the UI can
            // render a clickable card instead of a dead-looking one.
            boolean reachable = resp.status == 401
                    || resp.status == 403
                    || resp.status == 429;
            return LinkPreviewDto.builder()
                    .url(url).ok(false)
                    .finalUrl(finalUrl).status(resp.status)
                    .siteName(hostnameOf(finalUri))
                    .failureReason(reachable ? "access_restricted" : "status_" + resp.status)
                    .build();
        }

        String contentType = resp.contentType == null ? "" : resp.contentType.toLowerCase();
        if (!contentType.isEmpty()
                && !contentType.contains("text/html")
                && !contentType.contains("application/xhtml")) {
            // Non-HTML payloads (PDF, image, JSON) have no OG-tags. Return
            // a minimal "ok" card with the hostname so the UI still has
            // *something* to render — the file kind itself becomes the
            // de-facto preview ("PDF • example.com").
            return LinkPreviewDto.builder()
                    .url(url).ok(true)
                    .finalUrl(finalUrl).status(resp.status)
                    .title(decodeFileName(finalUri))
                    .siteName(hostnameOf(finalUri))
                    .type(typeFromContentType(contentType))
                    .build();
        }

        Document doc;
        try {
            doc = Jsoup.parse(resp.body == null ? "" : resp.body, finalUrl);
        } catch (Exception e) {
            return failedDto(url, "parse_failed: " + e.getClass().getSimpleName());
        }

        String ogTitle = metaAny(doc, "og:title");
        String ogDesc = metaAny(doc, "og:description");
        String ogImage = absolutise(metaAny(doc, "og:image"), finalUri);
        String ogSite = metaAny(doc, "og:site_name");
        String ogType = metaAny(doc, "og:type");

        // Twitter Cards spec uses <meta name="twitter:*"/> — but some
        // sites mirror them with property= for OG consistency. Try
        // both attributes so neither convention slips through.
        String twTitle = metaAny(doc, "twitter:title");
        String twDesc = metaAny(doc, "twitter:description");
        String twImage = absolutise(metaAny(doc, "twitter:image"), finalUri);

        String htmlTitle = doc.title();
        String htmlDesc = metaAny(doc, "description");

        String title = firstNonBlank(ogTitle, twTitle, htmlTitle);
        String description = firstNonBlank(ogDesc, twDesc, htmlDesc);
        String image = firstNonBlank(ogImage, twImage);
        String siteName = firstNonBlank(ogSite, hostnameOf(finalUri));
        String type = ogType == null ? null : ogType.toLowerCase();

        boolean usable = !isBlank(title) || !isBlank(description);
        if (!usable) {
            return LinkPreviewDto.builder()
                    .url(url).ok(false)
                    .finalUrl(finalUrl).status(resp.status)
                    .failureReason("no_metadata")
                    .build();
        }
        return LinkPreviewDto.builder()
                .url(url).ok(true)
                .title(nullIfBlank(title))
                .description(nullIfBlank(description))
                .image(nullIfBlank(image))
                .siteName(nullIfBlank(siteName))
                .type(nullIfBlank(type))
                .finalUrl(finalUrl)
                .status(resp.status)
                .build();
    }

    /**
     * Looks up a {@code <meta>} tag by either {@code property=} (OG
     * convention) or {@code name=} (Twitter / classic HTML), returning
     * the first non-blank {@code content} attribute. Necessary because
     * the two conventions are mixed in the wild — some pages duplicate
     * OG tags on {@code name=}, Twitter Cards use {@code name=} but
     * are sometimes mirrored on {@code property=}.
     */
    private static @Nullable String metaAny(Document doc, String key) {
        Element e = doc.selectFirst("meta[property=" + key + "]");
        if (e == null) {
            e = doc.selectFirst("meta[name=" + key + "]");
        }
        if (e == null) return null;
        String v = e.attr("content");
        return v.isBlank() ? null : v;
    }

    /**
     * Resolves a relative {@code og:image} URL against the final page
     * URL. Some sites publish their image as {@code /static/cover.jpg}
     * — Jsoup's base-URI machinery + URI.resolve handles that for us.
     */
    private static @Nullable String absolutise(@Nullable String value, URI base) {
        if (value == null || value.isBlank()) return null;
        try {
            URI resolved = base.resolve(value.trim());
            return resolved.toString();
        } catch (Exception e) {
            return value; // ship the raw value rather than swallowing it
        }
    }

    private static String hostnameOf(URI uri) {
        return uri.getHost() == null ? "" : uri.getHost();
    }

    private static String typeFromContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) return "binary";
        if (contentType.startsWith("application/pdf")) return "pdf";
        if (contentType.startsWith("image/")) return "image";
        if (contentType.startsWith("video/")) return "video";
        if (contentType.startsWith("audio/")) return "audio";
        return "binary";
    }

    private static String decodeFileName(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isEmpty()) return hostnameOf(uri);
        int slash = path.lastIndexOf('/');
        String tail = slash >= 0 && slash + 1 < path.length()
                ? path.substring(slash + 1) : path;
        return tail.isBlank() ? hostnameOf(uri) : tail;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static @Nullable String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private LinkPreviewDto failedDto(String url, String reason) {
        return LinkPreviewDto.builder()
                .url(url).ok(false).failureReason(reason).build();
    }

    private LinkPreviewDto fromCache(LinkPreviewCacheDocument doc) {
        return LinkPreviewDto.builder()
                .url(doc.getUrl())
                .ok(doc.isOk())
                .title(doc.getTitle())
                .description(doc.getDescription())
                .image(doc.getImage())
                .siteName(doc.getSiteName())
                .type(doc.getType())
                .finalUrl(doc.getFinalUrl())
                .status(doc.getStatus())
                .failureReason(doc.getFailureReason())
                .build();
    }

    private void persist(LinkPreviewDto dto, PreviewConfig cfg) {
        try {
            Instant now = Instant.now();
            Instant expire = dto.isOk()
                    ? now.plus(Duration.ofHours(cfg.cacheTtlOkHours))
                    : now.plus(Duration.ofMinutes(cfg.cacheTtlFailMinutes));
            Optional<LinkPreviewCacheDocument> existing = cache.findByUrl(dto.getUrl());
            LinkPreviewCacheDocument doc = existing.orElseGet(LinkPreviewCacheDocument::new);
            doc.setUrl(dto.getUrl());
            doc.setOk(dto.isOk());
            doc.setTitle(dto.getTitle());
            doc.setDescription(dto.getDescription());
            doc.setImage(dto.getImage());
            doc.setSiteName(dto.getSiteName());
            doc.setType(dto.getType());
            doc.setFinalUrl(dto.getFinalUrl());
            doc.setStatus(dto.getStatus());
            doc.setFailureReason(dto.getFailureReason());
            doc.setFetchedAt(now);
            doc.setExpireAt(expire);
            cache.save(doc);
        } catch (Exception e) {
            log.debug("LinkPreview: cache write failed for {}: {}", dto.getUrl(), e.toString());
        }
    }

    private PreviewConfig configFor(
            @Nullable String tenantId,
            @Nullable String projectId,
            @Nullable String processId) {
        return PreviewConfig.builder()
                .timeoutMs(intSetting(tenantId, projectId, processId,
                        SETTING_TIMEOUT_MS, DEFAULT_TIMEOUT_MS))
                .cacheTtlOkHours(intSetting(tenantId, projectId, processId,
                        SETTING_CACHE_TTL_OK_HOURS, DEFAULT_CACHE_TTL_OK_HOURS))
                .cacheTtlFailMinutes(intSetting(tenantId, projectId, processId,
                        SETTING_CACHE_TTL_FAIL_MINUTES, DEFAULT_CACHE_TTL_FAIL_MINUTES))
                .maxBodyBytes(intSetting(tenantId, projectId, processId,
                        SETTING_MAX_BODY_BYTES, DEFAULT_MAX_BODY_BYTES))
                .build();
    }

    private int intSetting(
            @Nullable String tenantId,
            @Nullable String projectId,
            @Nullable String processId,
            String key,
            int defaultValue) {
        if (tenantId == null || tenantId.isBlank()) return defaultValue;
        String raw = settings.getStringValueCascade(tenantId, projectId, processId, key);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            int v = Integer.parseInt(raw.trim());
            return v > 0 ? v : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Value
    @Builder
    static class PreviewConfig {
        int timeoutMs;
        int cacheTtlOkHours;
        int cacheTtlFailMinutes;
        int maxBodyBytes;
    }

    @Value
    @Builder
    static class PreviewHttpResponse {
        int status;
        @Nullable URI finalUri;
        @Nullable String contentType;
        @Nullable String body;
    }

    /** Test-seam: wraps the HTTP fetch behind a stubbable interface. */
    interface PreviewHttp {
        PreviewHttpResponse get(
                URI uri, Duration timeout, String userAgent, int maxBodyBytes) throws Exception;
    }

    static final class JdkPreviewHttp implements PreviewHttp {

        // Redirect.NEVER so SsrfGuard.sendGuarded re-checks every hop (F2).
        private final HttpClient http = SsrfGuard.guardedClientBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .build();

        @Override
        public PreviewHttpResponse get(
                URI uri, Duration timeout, String userAgent, int maxBodyBytes) throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .GET()
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
                    .header("Accept-Language", "en;q=0.9, de;q=0.8")
                    // No Range — the OG block is in <head> within the
                    // first ~4 KB usually, but some sites stuff late
                    // script tags before </head>; an explicit length
                    // limit after the fact is cheaper than fighting
                    // partial-response edge cases.
                    .timeout(timeout)
                    .build();
            HttpResponse<byte[]> response =
                    SsrfGuard.sendGuarded(http, request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] raw = response.body();
            if (raw != null && raw.length > maxBodyBytes) {
                byte[] capped = new byte[maxBodyBytes];
                System.arraycopy(raw, 0, capped, 0, maxBodyBytes);
                raw = capped;
            }
            String body = raw == null ? "" : new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            return PreviewHttpResponse.builder()
                    .status(response.statusCode())
                    .finalUri(response.uri())
                    .contentType(response.headers().firstValue("content-type").orElse(null))
                    .body(body)
                    .build();
        }
    }
}
