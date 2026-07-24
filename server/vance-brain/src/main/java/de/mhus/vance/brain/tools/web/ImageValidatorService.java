package de.mhus.vance.brain.tools.web;

import de.mhus.vance.shared.net.SsrfGuard;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.web.ImageUrlCacheDocument;
import de.mhus.vance.shared.web.ImageUrlCacheRepository;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Validates that image URLs actually resolve to an image payload —
 * not a 404, not a {@code 301 → /} WordPress fallback, not a
 * {@code text/html} placeholder. Used by {@code ImageSearchTool}
 * before returning search results to the LLM, so the model can
 * never embed a dead URL.
 *
 * <p>Validation is a HEAD request (browser-shaped headers, follow
 * redirects, ≤2s per URL). The verdict is cached in Mongo
 * ({@link ImageUrlCacheDocument}) — positive verdicts for a day,
 * negatives for half an hour. Popular sources (Pixabay, Wikipedia
 * CDN) hit cache after the first search.
 *
 * <p>Acceptance rules:
 *
 * <ul>
 *   <li>Final status 200 AND {@code Content-Type} begins with
 *       {@code image/} → ok.</li>
 *   <li>HEAD returns 405/501 (server refuses the method) → fall back
 *       to {@code GET} with {@code Range: bytes=0-1023} and sniff the
 *       magic number (JPEG, PNG, GIF, WebP, BMP, ICO, SVG).</li>
 *   <li>Final URL collapses to path {@code /} → reject. Classic
 *       host-pattern when an image was deleted and the CMS redirects
 *       to the homepage (the curl/-v reproducer in the 2026-05-26
 *       Lisbon thread).</li>
 *   <li>Anything else (4xx, 5xx, text/html, timeout, malformed URL)
 *       → reject.</li>
 * </ul>
 *
 * <p>Tuning: {@code web.image.validator.*} settings via the project
 * cascade — {@code timeoutMs} (default 2000),
 * {@code totalBudgetMs} (default 5000), {@code cacheTtlOkHours}
 * (default 24), {@code cacheTtlFailMinutes} (default 30),
 * {@code maxConcurrent} (default 10).
 */
@Service
@Slf4j
public class ImageValidatorService {

    static final String SETTING_TIMEOUT_MS = "web.image.validator.timeoutMs";
    static final String SETTING_TOTAL_BUDGET_MS = "web.image.validator.totalBudgetMs";
    static final String SETTING_CACHE_TTL_OK_HOURS = "web.image.validator.cacheTtlOkHours";
    static final String SETTING_CACHE_TTL_FAIL_MINUTES = "web.image.validator.cacheTtlFailMinutes";
    static final String SETTING_MAX_CONCURRENT = "web.image.validator.maxConcurrent";

    private static final int DEFAULT_TIMEOUT_MS = 2000;
    private static final int DEFAULT_TOTAL_BUDGET_MS = 5000;
    private static final int DEFAULT_CACHE_TTL_OK_HOURS = 24;
    private static final int DEFAULT_CACHE_TTL_FAIL_MINUTES = 30;
    private static final int DEFAULT_MAX_CONCURRENT = 10;

    /**
     * Browser-shaped User-Agent. Some image CDNs (notably Cloudflare-
     * fronted hosts and several WordPress plugins) reject bare bot
     * UAs with 403. Identifying as a modern desktop browser is the
     * simplest way to get a representative answer about whether the
     * resource is reachable for the eventual {@code <img>} fetch.
     */
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";

    private final ImageUrlCacheRepository cache;
    private final SettingService settings;
    private final ImageValidatorHttp http;

    @Autowired
    public ImageValidatorService(
            ImageUrlCacheRepository cache, SettingService settings) {
        this(cache, settings, new JdkImageValidatorHttp());
    }

    /** Test-seam constructor — lets unit tests inject a stubbed HTTP client. */
    ImageValidatorService(
            ImageUrlCacheRepository cache,
            SettingService settings,
            ImageValidatorHttp http) {
        this.cache = cache;
        this.settings = settings;
        this.http = http;
    }

    /**
     * Validates a batch of URLs in parallel within the configured
     * budget. Returns one entry per input URL, in input order. URLs
     * that exceed the total budget land with {@code ok=false} —
     * silent drop is the caller's responsibility.
     */
    public List<ValidationResult> validate(
            Collection<String> urls,
            @Nullable String tenantId,
            @Nullable String projectId,
            @Nullable String processId) {
        if (urls == null || urls.isEmpty()) return List.of();
        ValidatorConfig cfg = configFor(tenantId, projectId, processId);
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.max(1, Math.min(cfg.maxConcurrent, urls.size())));
        try {
            Map<String, CompletableFuture<ValidationResult>> futures = new LinkedHashMap<>();
            for (String url : urls) {
                if (futures.containsKey(url)) continue; // dedup within a batch
                futures.put(url,
                        CompletableFuture.supplyAsync(() -> validateOneSafely(url, cfg), pool));
            }
            CompletableFuture<Void> all = CompletableFuture.allOf(
                    futures.values().toArray(new CompletableFuture[0]));
            try {
                all.get(cfg.totalBudgetMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.warn("ImageValidator: total budget {}ms exhausted, "
                        + "{} of {} URLs incomplete",
                        cfg.totalBudgetMs,
                        futures.values().stream().filter(f -> !f.isDone()).count(),
                        futures.size());
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }
            Map<String, ValidationResult> byUrl = new HashMap<>();
            for (Map.Entry<String, CompletableFuture<ValidationResult>> e : futures.entrySet()) {
                ValidationResult result;
                if (e.getValue().isDone() && !e.getValue().isCompletedExceptionally()) {
                    try {
                        result = e.getValue().get();
                    } catch (Exception inner) {
                        result = ValidationResult.failed(e.getKey(), "future_failed: " + inner.getMessage());
                    }
                } else {
                    e.getValue().cancel(true);
                    result = ValidationResult.failed(e.getKey(), "budget_exhausted");
                }
                byUrl.put(e.getKey(), result);
            }
            List<ValidationResult> out = new ArrayList<>(urls.size());
            for (String url : urls) {
                out.add(byUrl.getOrDefault(url,
                        ValidationResult.failed(url, "duplicate_in_batch")));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Single-URL validation with the configured cache + HTTP probe. */
    public ValidationResult validateOne(
            String url,
            @Nullable String tenantId,
            @Nullable String projectId,
            @Nullable String processId) {
        if (url == null || url.isBlank()) {
            return ValidationResult.failed(url == null ? "" : url, "blank_url");
        }
        return validateOneSafely(url, configFor(tenantId, projectId, processId));
    }

    private ValidationResult validateOneSafely(String url, ValidatorConfig cfg) {
        try {
            Optional<ImageUrlCacheDocument> cached = cache.findByUrl(url);
            if (cached.isPresent()) {
                ImageUrlCacheDocument doc = cached.get();
                Instant exp = doc.getExpireAt();
                if (exp == null || exp.isAfter(Instant.now())) {
                    return ValidationResult.fromCache(doc);
                }
            }
            ValidationResult result = probe(url, cfg);
            persist(result, cfg);
            return result;
        } catch (Exception e) {
            log.debug("ImageValidator: unexpected failure for {}: {}", url, e.toString());
            return ValidationResult.failed(url, "exception: " + e.getClass().getSimpleName());
        }
    }

    private ValidationResult probe(String url, ValidatorConfig cfg) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return ValidationResult.failed(url, "invalid_uri");
        }
        if (uri.getScheme() == null
                || !(uri.getScheme().equalsIgnoreCase("http")
                        || uri.getScheme().equalsIgnoreCase("https"))) {
            return ValidationResult.failed(url, "scheme_not_http");
        }

        ProbeResponse head;
        try {
            head = http.head(uri, Duration.ofMillis(cfg.timeoutMs), BROWSER_USER_AGENT);
        } catch (Exception e) {
            return ValidationResult.failed(url, "head_failed: " + e.getClass().getSimpleName());
        }

        // Server refuses HEAD — try a small Range GET as a fallback.
        // Some image CDNs (S3, Lambda image-resize, older WordPress)
        // answer HEAD with 405/501. A bytes 0-1023 GET still gets us
        // enough to read the magic number without downloading the
        // whole picture.
        if (head.status == 405 || head.status == 501) {
            try {
                head = http.getRange(uri, Duration.ofMillis(cfg.timeoutMs), BROWSER_USER_AGENT);
            } catch (Exception e) {
                return ValidationResult.failed(url, "range_get_failed: " + e.getClass().getSimpleName());
            }
        }

        String finalUrl = head.finalUri == null ? url : head.finalUri.toString();
        URI finalUri = head.finalUri == null ? uri : head.finalUri;

        // WordPress + similar pattern: image was deleted, host
        // redirects every missing path to /. status looks 200 but
        // the response body is the homepage. The path-collapse
        // check catches it before we ever look at content-type.
        if (head.status >= 300) {
            return ValidationResult.failed(url, "status_" + head.status, finalUrl, head.status, null);
        }
        String path = finalUri.getPath();
        if (path == null || path.isEmpty() || "/".equals(path) || "/index.html".equalsIgnoreCase(path)) {
            return ValidationResult.failed(url, "homepage_fallback", finalUrl, head.status,
                    head.contentType);
        }

        String ct = head.contentType == null ? "" : head.contentType.toLowerCase();
        int semi = ct.indexOf(';');
        if (semi > 0) ct = ct.substring(0, semi).trim();

        if (ct.startsWith("image/")) {
            return ValidationResult.ok(url, finalUrl, head.status, ct);
        }

        // Some image responses ship without an explicit Content-Type
        // header or come back as application/octet-stream. Magic-number
        // sniff on the first few bytes — the GET-Range fallback already
        // delivered them, the HEAD path tries one more byte-Range only
        // when the body is empty.
        byte[] sample = head.sample;
        if (sample == null || sample.length == 0) {
            try {
                sample = http.getRange(uri, Duration.ofMillis(cfg.timeoutMs), BROWSER_USER_AGENT).sample;
            } catch (Exception ignored) {
                sample = null;
            }
        }
        if (sample != null && looksLikeImageBytes(sample)) {
            return ValidationResult.ok(url, finalUrl, head.status, ct.isEmpty() ? "image/?" : ct);
        }
        return ValidationResult.failed(url, "content_type_" + (ct.isEmpty() ? "missing" : ct),
                finalUrl, head.status, ct);
    }

    /**
     * Magic-number sniff. Covers JPEG, PNG, GIF, WebP, BMP, ICO and
     * SVG/XML. SVG comes through as text; the XML prolog or
     * {@code <svg } prefix is the giveaway. Sample needs at least
     * 12 bytes to reliably catch WebP — that's what the RangeGET
     * fallback fetches.
     */
    static boolean looksLikeImageBytes(byte[] b) {
        if (b == null || b.length < 4) return false;
        // JPEG
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return true;
        // PNG
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return true;
        // GIF87a / GIF89a
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8'
                && (b[4] == '7' || b[4] == '9') && b[5] == 'a') return true;
        // WebP: RIFF....WEBP
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return true;
        // BMP
        if (b[0] == 'B' && b[1] == 'M') return true;
        // ICO: 00 00 01 00
        if (b[0] == 0x00 && b[1] == 0x00 && b[2] == 0x01 && b[3] == 0x00) return true;
        // SVG / XML
        String head = new String(b, 0, Math.min(b.length, 256)).trim().toLowerCase();
        if (head.startsWith("<?xml") || head.startsWith("<svg")) return true;
        return false;
    }

    private void persist(ValidationResult result, ValidatorConfig cfg) {
        try {
            Instant now = Instant.now();
            Instant expire = result.isOk()
                    ? now.plus(Duration.ofHours(cfg.cacheTtlOkHours))
                    : now.plus(Duration.ofMinutes(cfg.cacheTtlFailMinutes));
            Optional<ImageUrlCacheDocument> existing = cache.findByUrl(result.getUrl());
            ImageUrlCacheDocument doc = existing.orElseGet(ImageUrlCacheDocument::new);
            doc.setUrl(result.getUrl());
            doc.setOk(result.isOk());
            doc.setContentType(result.getContentType());
            doc.setFinalUrl(result.getFinalUrl());
            doc.setStatus(result.getStatus());
            doc.setValidatedAt(now);
            doc.setExpireAt(expire);
            cache.save(doc);
        } catch (Exception e) {
            // Cache write failure is non-fatal — the verdict still
            // reaches the caller, the next probe just does the
            // HTTP work again.
            log.debug("ImageValidator: cache write failed for {}: {}",
                    result.getUrl(), e.toString());
        }
    }

    private ValidatorConfig configFor(
            @Nullable String tenantId,
            @Nullable String projectId,
            @Nullable String processId) {
        return ValidatorConfig.builder()
                .timeoutMs(intSetting(tenantId, projectId, processId,
                        SETTING_TIMEOUT_MS, DEFAULT_TIMEOUT_MS))
                .totalBudgetMs(intSetting(tenantId, projectId, processId,
                        SETTING_TOTAL_BUDGET_MS, DEFAULT_TOTAL_BUDGET_MS))
                .cacheTtlOkHours(intSetting(tenantId, projectId, processId,
                        SETTING_CACHE_TTL_OK_HOURS, DEFAULT_CACHE_TTL_OK_HOURS))
                .cacheTtlFailMinutes(intSetting(tenantId, projectId, processId,
                        SETTING_CACHE_TTL_FAIL_MINUTES, DEFAULT_CACHE_TTL_FAIL_MINUTES))
                .maxConcurrent(intSetting(tenantId, projectId, processId,
                        SETTING_MAX_CONCURRENT, DEFAULT_MAX_CONCURRENT))
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

    /** Validator-tuning bundle. Resolved per call from the settings cascade. */
    @Value
    @Builder
    static class ValidatorConfig {
        int timeoutMs;
        int totalBudgetMs;
        int cacheTtlOkHours;
        int cacheTtlFailMinutes;
        int maxConcurrent;
    }

    /** Single-URL verdict — returned to the caller and persisted. */
    @Value
    @Builder
    public static class ValidationResult {
        String url;
        boolean ok;
        @Nullable String reason;
        @Nullable String finalUrl;
        int status;
        @Nullable String contentType;

        public static ValidationResult ok(String url, String finalUrl, int status, String contentType) {
            return ValidationResult.builder()
                    .url(url).ok(true).reason(null)
                    .finalUrl(finalUrl).status(status).contentType(contentType).build();
        }

        public static ValidationResult failed(String url, String reason) {
            return ValidationResult.builder()
                    .url(url).ok(false).reason(reason).finalUrl(null).status(0).contentType(null).build();
        }

        public static ValidationResult failed(
                String url, String reason, String finalUrl, int status, @Nullable String contentType) {
            return ValidationResult.builder()
                    .url(url).ok(false).reason(reason)
                    .finalUrl(finalUrl).status(status).contentType(contentType).build();
        }

        public static ValidationResult fromCache(ImageUrlCacheDocument doc) {
            return ValidationResult.builder()
                    .url(doc.getUrl())
                    .ok(doc.isOk())
                    .reason(doc.isOk() ? null : "cached_failure")
                    .finalUrl(doc.getFinalUrl())
                    .status(doc.getStatus())
                    .contentType(doc.getContentType())
                    .build();
        }
    }

    /**
     * Test-seam: the validator wraps HTTP calls behind this so unit
     * tests can stub responses without a real network. Production
     * uses {@link JdkImageValidatorHttp}.
     */
    interface ImageValidatorHttp {
        ProbeResponse head(URI uri, Duration timeout, String userAgent) throws Exception;

        ProbeResponse getRange(URI uri, Duration timeout, String userAgent) throws Exception;
    }

    /** Minimal response carrier so the validator code stays HTTP-shape-agnostic. */
    @Value
    @Builder
    static class ProbeResponse {
        int status;
        @Nullable URI finalUri;
        @Nullable String contentType;
        byte @Nullable [] sample;
    }

    /** Production HTTP implementation. */
    static final class JdkImageValidatorHttp implements ImageValidatorHttp {

        // Redirect.NEVER so SsrfGuard.sendGuarded re-checks every hop — the
        // probed image URL is LLM-/search-supplied (untrusted), so a
        // redirect into the internal network must be blocked (code-review F2).
        private final HttpClient http = SsrfGuard.guardedClientBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        @Override
        public ProbeResponse head(URI uri, Duration timeout, String userAgent) throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .header("User-Agent", userAgent)
                    .header("Accept", "image/*,*/*;q=0.8")
                    .timeout(timeout)
                    .build();
            HttpResponse<Void> response = SsrfGuard.sendGuarded(http, request, HttpResponse.BodyHandlers.discarding());
            return ProbeResponse.builder()
                    .status(response.statusCode())
                    .finalUri(response.uri())
                    .contentType(response.headers().firstValue("content-type").orElse(null))
                    .sample(null)
                    .build();
        }

        @Override
        public ProbeResponse getRange(URI uri, Duration timeout, String userAgent) throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .GET()
                    .header("User-Agent", userAgent)
                    .header("Accept", "image/*,*/*;q=0.8")
                    .header("Range", "bytes=0-1023")
                    .timeout(timeout)
                    .build();
            HttpResponse<byte[]> response = SsrfGuard.sendGuarded(http, request,
                    SsrfGuard.capped(HttpResponse.BodyHandlers.ofByteArray()));
            return ProbeResponse.builder()
                    .status(response.statusCode())
                    .finalUri(response.uri())
                    .contentType(response.headers().firstValue("content-type").orElse(null))
                    .sample(response.body())
                    .build();
        }
    }

    // Visible-for-test helpers ---------------------------------------

    static List<String> defaultsAsListForDocs() {
        return Collections.unmodifiableList(List.of(
                SETTING_TIMEOUT_MS + " = " + DEFAULT_TIMEOUT_MS,
                SETTING_TOTAL_BUDGET_MS + " = " + DEFAULT_TOTAL_BUDGET_MS,
                SETTING_CACHE_TTL_OK_HOURS + " = " + DEFAULT_CACHE_TTL_OK_HOURS,
                SETTING_CACHE_TTL_FAIL_MINUTES + " = " + DEFAULT_CACHE_TTL_FAIL_MINUTES,
                SETTING_MAX_CONCURRENT + " = " + DEFAULT_MAX_CONCURRENT));
    }
}
