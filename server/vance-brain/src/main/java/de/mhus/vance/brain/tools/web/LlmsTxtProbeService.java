package de.mhus.vance.brain.tools.web;

import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.web.OverviewStatus;
import de.mhus.vance.shared.web.WebOriginOverviewDocument;
import de.mhus.vance.shared.web.WebOriginOverviewService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Speculative probe for {@code /llms.txt} on the origin of an
 * about-to-be-fetched URL. Two layers:
 *
 * <ul>
 *   <li>A <b>setting check</b> via the project-cascade
 *       ({@link SettingService#getStringValueCascade}) — when
 *       {@code web.llms_txt.auto_probe} resolves to {@code false} the
 *       probe is skipped entirely. Default is {@code true}.</li>
 *   <li>A <b>cache check</b> via {@link WebOriginOverviewService} keyed
 *       by canonical origin. The cache is global (no tenant), since a
 *       site's {@code llms.txt} is identical for everyone.</li>
 * </ul>
 *
 * <p>On a cache miss the service issues a single GET against
 * {@code <origin>/llms.txt} with a short timeout, classifies the
 * response into {@link OverviewStatus}, and writes the outcome with a
 * status-dependent TTL (see {@link #SETTING_TTL_HOURS},
 * {@link #SETTING_NEGATIVE_TTL_HOURS}, {@link #SETTING_ERROR_TTL_MINUTES}).
 *
 * <p>{@link #probe(URI, ToolInvocationContext)} never throws — a
 * probe is opportunistic, never blocking the actual user fetch from
 * succeeding. Failures degrade silently to {@link Optional#empty()}.
 */
@Component
@Slf4j
public class LlmsTxtProbeService {

    /** Setting keys (project cascade, defaults below). */
    public static final String SETTING_AUTO_PROBE = "web.llms_txt.auto_probe";
    public static final String SETTING_TTL_HOURS = "web.llms_txt.ttl_hours";
    public static final String SETTING_NEGATIVE_TTL_HOURS = "web.llms_txt.negative_ttl_hours";
    public static final String SETTING_ERROR_TTL_MINUTES = "web.llms_txt.error_ttl_minutes";
    public static final String SETTING_MAX_CONTENT_CHARS = "web.llms_txt.max_content_chars";

    static final boolean DEFAULT_AUTO_PROBE = true;
    static final long DEFAULT_TTL_HOURS = 168;            // 7 days for OK
    static final long DEFAULT_NEGATIVE_TTL_HOURS = 24;    // 1 day for confirmed-absent
    static final long DEFAULT_ERROR_TTL_MINUTES = 60;     // 1 hour for transient failures
    static final int DEFAULT_MAX_CONTENT_CHARS = 50_000;

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
    private static final String USER_AGENT =
            "Vance-Brain/0.1 (+https://github.com/mhus/vance) llms.txt-probe";
    private static final String LLMS_TXT_PATH = "/llms.txt";

    private final WebOriginOverviewService cache;
    private final SettingService settings;
    private final HttpClient http;

    @Autowired
    public LlmsTxtProbeService(
            WebOriginOverviewService cache, SettingService settings) {
        this(cache, settings, HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(PROBE_TIMEOUT)
                .build());
    }

    LlmsTxtProbeService(
            WebOriginOverviewService cache,
            SettingService settings,
            HttpClient http) {
        this.cache = cache;
        this.settings = settings;
        this.http = http;
    }

    /**
     * Returns the cached or freshly fetched {@code llms.txt} body for
     * the origin of {@code url}, or {@link Optional#empty()} when
     * probing is disabled, the origin has no {@code llms.txt}, or any
     * step failed.
     */
    public Optional<String> probe(URI url, ToolInvocationContext ctx) {
        if (!isAutoProbeEnabled(ctx)) {
            return Optional.empty();
        }
        Optional<String> originOpt = WebOriginOverviewService.originOf(url);
        if (originOpt.isEmpty()) {
            return Optional.empty();
        }
        String origin = originOpt.get();

        Optional<WebOriginOverviewDocument> cached = cache.findByOrigin(origin);
        if (cached.isPresent()) {
            WebOriginOverviewDocument doc = cached.get();
            return doc.getStatus() == OverviewStatus.OK
                    ? Optional.ofNullable(doc.getContent())
                    : Optional.empty();
        }

        return fetchAndCache(origin, ctx);
    }

    private boolean isAutoProbeEnabled(ToolInvocationContext ctx) {
        if (ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            return DEFAULT_AUTO_PROBE;
        }
        String raw = settings.getStringValueCascade(
                ctx.tenantId(), ctx.projectId(), ctx.processId(), SETTING_AUTO_PROBE);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_AUTO_PROBE;
        }
        String norm = raw.trim().toLowerCase();
        return "true".equals(norm) || "1".equals(norm) || "yes".equals(norm) || "on".equals(norm);
    }

    private Optional<String> fetchAndCache(String origin, ToolInvocationContext ctx) {
        URI probeUri;
        try {
            probeUri = new URI(origin + LLMS_TXT_PATH);
        } catch (URISyntaxException e) {
            log.debug("llms.txt probe skipped — non-URI origin '{}': {}", origin, e.getMessage());
            return Optional.empty();
        }

        int maxChars = readIntSetting(ctx, SETTING_MAX_CONTENT_CHARS, DEFAULT_MAX_CONTENT_CHARS);
        Duration okTtl = Duration.ofHours(readLongSetting(
                ctx, SETTING_TTL_HOURS, DEFAULT_TTL_HOURS));
        Duration absentTtl = Duration.ofHours(readLongSetting(
                ctx, SETTING_NEGATIVE_TTL_HOURS, DEFAULT_NEGATIVE_TTL_HOURS));
        Duration errorTtl = Duration.ofMinutes(readLongSetting(
                ctx, SETTING_ERROR_TTL_MINUTES, DEFAULT_ERROR_TTL_MINUTES));

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(probeUri)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/plain, text/markdown, */*;q=0.5")
                    .timeout(PROBE_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(
                    request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            if (status == 200) {
                String body = response.body() == null ? "" : response.body();
                int fullLength = body.length();
                String content = fullLength > maxChars
                        ? body.substring(0, maxChars) : body;
                cache.record(origin, OverviewStatus.OK, content, fullLength, okTtl);
                log.debug("llms.txt probe origin='{}' OK bytes={} cached={}h",
                        origin, fullLength, okTtl.toHours());
                return Optional.of(content);
            }
            if (status == 404 || status == 410 || status == 451) {
                cache.record(origin, OverviewStatus.NOT_FOUND, null, 0, absentTtl);
                log.debug("llms.txt probe origin='{}' NOT_FOUND status={} cached={}h",
                        origin, status, absentTtl.toHours());
                return Optional.empty();
            }
            cache.record(origin, OverviewStatus.ERROR, null, 0, errorTtl);
            log.debug("llms.txt probe origin='{}' ERROR status={} cached={}m",
                    origin, status, errorTtl.toMinutes());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            cache.record(origin, OverviewStatus.ERROR, null, 0, errorTtl);
            log.debug("llms.txt probe origin='{}' transport-error={} cached={}m",
                    origin, e.toString(), errorTtl.toMinutes());
            return Optional.empty();
        }
    }

    private long readLongSetting(ToolInvocationContext ctx, String key, long defaultValue) {
        if (ctx.tenantId() == null || ctx.tenantId().isBlank()) return defaultValue;
        String raw = settings.getStringValueCascade(
                ctx.tenantId(), ctx.projectId(), ctx.processId(), key);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid long setting '{}'='{}' — using default {}", key, raw, defaultValue);
            return defaultValue;
        }
    }

    private int readIntSetting(ToolInvocationContext ctx, String key, int defaultValue) {
        long v = readLongSetting(ctx, key, defaultValue);
        if (v < 0) return defaultValue;
        if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) v;
    }
}
