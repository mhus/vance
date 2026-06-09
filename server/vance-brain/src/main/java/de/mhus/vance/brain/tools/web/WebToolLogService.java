package de.mhus.vance.brain.tools.web;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.settings.SettingService;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Writes a per-call summary document for {@code web_search} and
 * {@code web_fetch} invocations — same shape and lifecycle pattern as
 * {@link de.mhus.vance.brain.ursascheduler.SchedulerLogService} and
 * {@link de.mhus.vance.brain.ursaeventtrigger.UrsaEventLogService}.
 *
 * <p>The document captures <em>what the LLM actually saw</em>: the
 * truncated body for fetches, the parsed hit list for searches —
 * never the unredacted upstream response. That keeps forensics
 * faithful to the prompt history and avoids the "Mongo holds more
 * than the LLM ever read" footgun.
 *
 * <p>Failed calls are intentionally <b>not</b> logged. Errors travel
 * back to the engine in the regular tool response (or a thrown
 * {@code ToolException}); a broken endpoint would otherwise generate
 * a flood of documents without diagnostic value.
 *
 * <p>Retention is tri-state per setting (project cascade), independent
 * per tool because volume profiles differ — search is high-frequency
 * but small, fetch is low-frequency but potentially large:
 * <ul>
 *   <li>{@code > 0} — document is written with
 *       {@code expiresAt = firedAt + days}, clamped to
 *       {@code <= MAX_RETENTION_DAYS}.</li>
 *   <li>{@code 0} — document is written without {@code expiresAt};
 *       MongoDB never reaps it (infinite retention).</li>
 *   <li>{@code < 0} — disabled; no document is written.</li>
 * </ul>
 */
@Service
@Slf4j
public class WebToolLogService {

    public static final String LOG_FOLDER_SEARCH = "_vance/logs/web/search/";
    public static final String LOG_FOLDER_FETCH = "_vance/logs/web/fetch/";
    public static final String KIND_SEARCH = "web-search-log";
    public static final String KIND_FETCH = "web-fetch-log";

    /** Setting key for {@code web_search} retention. Tri-state. */
    public static final String SETTING_SEARCH_RETENTION = "web.search.log.retentionDays";

    /** Setting key for {@code web_fetch} retention. Tri-state. */
    public static final String SETTING_FETCH_RETENTION = "web.fetch.log.retentionDays";

    private static final int MAX_RETENTION_DAYS = 365;

    private static final DateTimeFormatter STAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'")
                    .withZone(ZoneOffset.UTC);

    private final DocumentService documentService;
    private final SettingService settingService;
    private final int defaultSearchRetentionDays;
    private final int defaultFetchRetentionDays;

    public WebToolLogService(
            DocumentService documentService,
            SettingService settingService,
            @Value("${vance.web.search.log.retention-days:7}") int defaultSearchRetentionDays,
            @Value("${vance.web.fetch.log.retention-days:7}")  int defaultFetchRetentionDays) {
        this.documentService = documentService;
        this.settingService = settingService;
        // Clamp upper bound only; 0 = infinite, <0 = disabled are
        // valid tri-state signals.
        this.defaultSearchRetentionDays = Math.min(MAX_RETENTION_DAYS, defaultSearchRetentionDays);
        this.defaultFetchRetentionDays  = Math.min(MAX_RETENTION_DAYS, defaultFetchRetentionDays);
        log.info("WebToolLogService initialised — search={}d, fetch={}d "
                        + "(0 = infinite, <0 = disabled; tenant overrides via '{}' / '{}')",
                describe(this.defaultSearchRetentionDays),
                describe(this.defaultFetchRetentionDays),
                SETTING_SEARCH_RETENTION, SETTING_FETCH_RETENTION);
    }

    private static String describe(int days) {
        if (days < 0) return "DISABLED";
        if (days == 0) return "INFINITE";
        return days + "d";
    }

    // ─── Public entry points ───

    /**
     * Captured result of a {@code web_search} call. Each row is one
     * organic hit as the tool exposed it to the LLM
     * ({@code title} / {@code url} / {@code snippet}).
     */
    public record SearchOutcome(
            String tenantId,
            @Nullable String projectId,
            String query,
            int requestedNum,
            int returnedCount,
            List<Map<String, Object>> hits,
            Instant firedAt,
            long durationMs,
            @Nullable String calledBy) {

        public static String mintCorrelationId() {
            return "web_" + UUID.randomUUID();
        }
    }

    /**
     * Captured result of a {@code web_fetch} call. {@code bodyShown}
     * is the truncated body the tool returned to the LLM, NOT the
     * full upstream response.
     */
    public record FetchOutcome(
            String tenantId,
            @Nullable String projectId,
            String url,
            int statusCode,
            @Nullable String contentType,
            int fullLength,
            boolean truncated,
            boolean transformedFromHtml,
            String bodyShown,
            Instant firedAt,
            long durationMs,
            @Nullable String calledBy) {

        public static String mintCorrelationId() {
            return "web_" + UUID.randomUUID();
        }
    }

    public void recordSearch(String correlationId, SearchOutcome out) {
        if (out.projectId() == null || out.projectId().isBlank()) {
            // No project context (e.g. tool invoked outside any
            // think-process) — we have nowhere to file the document.
            // Search/fetch logging requires a project the same way
            // scheduler-log does; skip silently.
            return;
        }
        int days = retentionDaysFor(out.tenantId(), out.projectId(),
                SETTING_SEARCH_RETENTION, defaultSearchRetentionDays);
        if (days < 0) {
            log.trace("WebToolLogService — search write skipped (disabled) for '{}/{}'",
                    out.tenantId(), out.projectId());
            return;
        }
        String path = LOG_FOLDER_SEARCH
                + STAMP_FMT.format(out.firedAt()) + "-" + correlationId + ".md";
        String body = renderSearchMarkdown(correlationId, out);
        Instant expiresAt = days == 0 ? null
                : out.firedAt().plusSeconds(Duration.ofDays(days).toSeconds());
        upsert(out.tenantId(), out.projectId(), path,
                "web_search — " + truncate(out.query(), 80),
                List.of(KIND_SEARCH, "web-log"),
                body, expiresAt, correlationId);
    }

    public void recordFetch(String correlationId, FetchOutcome out) {
        if (out.projectId() == null || out.projectId().isBlank()) {
            return;
        }
        int days = retentionDaysFor(out.tenantId(), out.projectId(),
                SETTING_FETCH_RETENTION, defaultFetchRetentionDays);
        if (days < 0) {
            log.trace("WebToolLogService — fetch write skipped (disabled) for '{}/{}'",
                    out.tenantId(), out.projectId());
            return;
        }
        String path = LOG_FOLDER_FETCH
                + STAMP_FMT.format(out.firedAt()) + "-" + correlationId + ".md";
        String body = renderFetchMarkdown(correlationId, out);
        Instant expiresAt = days == 0 ? null
                : out.firedAt().plusSeconds(Duration.ofDays(days).toSeconds());
        upsert(out.tenantId(), out.projectId(), path,
                "web_fetch — " + truncate(out.url(), 80),
                List.of(KIND_FETCH, "web-log"),
                body, expiresAt, correlationId);
    }

    // ─── Internals ───

    private void upsert(String tenantId, String projectId, String path,
                        String title, List<String> tags,
                        String body, @Nullable Instant expiresAt,
                        String correlationId) {
        try {
            documentService.upsertEphemeralText(
                    tenantId, projectId, path, title, tags, body,
                    /*createdBy*/ "web-tool", expiresAt);
        } catch (RuntimeException ex) {
            // Diagnostics writes must never break the calling tool —
            // the engine already has its result; the document is just
            // an audit shadow.
            log.warn("WebToolLogService upsert failed for '{}/{}' correlationId={} path='{}': {}",
                    tenantId, projectId, correlationId, path, ex.toString());
        }
    }

    private int retentionDaysFor(String tenantId, String projectId,
                                 String settingKey, int defaultDays) {
        String raw = settingService.getStringValueCascade(
                tenantId, projectId, /*thinkProcessId*/ null, settingKey);
        int days = defaultDays;
        if (raw != null && !raw.isBlank()) {
            try {
                days = Integer.parseInt(raw.trim());
            } catch (NumberFormatException ex) {
                log.warn("WebToolLogService — setting '{}' is not an integer ('{}'), falling back to default {}d",
                        settingKey, raw, defaultDays);
            }
        }
        if (days <= 0) return days;
        return Math.min(MAX_RETENTION_DAYS, days);
    }

    private static String renderSearchMarkdown(String correlationId, SearchOutcome out) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("kind: ").append(KIND_SEARCH).append('\n');
        sb.append("correlationId: ").append(correlationId).append('\n');
        sb.append("query: ").append(yamlScalar(out.query())).append('\n');
        sb.append("requestedNum: ").append(out.requestedNum()).append('\n');
        sb.append("returnedCount: ").append(out.returnedCount()).append('\n');
        sb.append("firedAt: ").append(out.firedAt()).append('\n');
        sb.append("durationMs: ").append(out.durationMs()).append('\n');
        if (out.calledBy() != null) sb.append("calledBy: ").append(yamlScalar(out.calledBy())).append('\n');
        sb.append("---\n\n");

        sb.append("# web_search — ").append(truncate(out.query(), 100)).append("\n\n");
        sb.append("- **Fired:** ").append(out.firedAt())
                .append(" (").append(out.durationMs()).append(" ms)\n");
        sb.append("- **Query:** `").append(out.query()).append("`\n");
        sb.append("- **Results:** ").append(out.returnedCount())
                .append(" / ").append(out.requestedNum()).append(" requested\n");
        if (out.calledBy() != null) sb.append("- **Called by:** ").append(out.calledBy()).append('\n');

        sb.append("\n## Hits\n\n");
        if (out.hits().isEmpty()) {
            sb.append("_(no results)_\n");
        } else {
            int i = 1;
            for (Map<String, Object> hit : out.hits()) {
                Object titleObj = hit.get("title");
                Object urlObj = hit.get("url");
                Object snippetObj = hit.get("snippet");
                sb.append(i++).append(". **").append(titleObj == null ? "(no title)" : titleObj.toString()).append("**\n");
                if (urlObj != null) sb.append("   ").append(urlObj).append('\n');
                if (snippetObj != null && !snippetObj.toString().isBlank()) {
                    sb.append("   > ").append(snippetObj.toString().replace("\n", " ")).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static String renderFetchMarkdown(String correlationId, FetchOutcome out) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("kind: ").append(KIND_FETCH).append('\n');
        sb.append("correlationId: ").append(correlationId).append('\n');
        sb.append("url: ").append(yamlScalar(out.url())).append('\n');
        sb.append("statusCode: ").append(out.statusCode()).append('\n');
        if (out.contentType() != null) sb.append("contentType: ").append(yamlScalar(out.contentType())).append('\n');
        sb.append("fullLength: ").append(out.fullLength()).append('\n');
        sb.append("truncated: ").append(out.truncated()).append('\n');
        if (out.transformedFromHtml()) sb.append("transformedFromHtml: true\n");
        sb.append("firedAt: ").append(out.firedAt()).append('\n');
        sb.append("durationMs: ").append(out.durationMs()).append('\n');
        if (out.calledBy() != null) sb.append("calledBy: ").append(yamlScalar(out.calledBy())).append('\n');
        sb.append("---\n\n");

        sb.append("# web_fetch — ").append(truncate(out.url(), 100)).append("\n\n");
        sb.append("- **Fired:** ").append(out.firedAt())
                .append(" (").append(out.durationMs()).append(" ms)\n");
        sb.append("- **URL:** ").append(out.url()).append('\n');
        sb.append("- **Status:** ").append(out.statusCode()).append('\n');
        if (out.contentType() != null) sb.append("- **Content-Type:** ").append(out.contentType()).append('\n');
        sb.append("- **Length:** ").append(out.fullLength())
                .append(out.truncated() ? " (truncated)" : "").append('\n');
        if (out.transformedFromHtml()) sb.append("- **HTML → text** by the fetch tool\n");
        if (out.calledBy() != null) sb.append("- **Called by:** ").append(out.calledBy()).append('\n');

        sb.append("\n## Body (as seen by the LLM)\n\n");
        // Markdown fenced code block — keeps the body intact as
        // plaintext, no risk of front-matter or list-marker collisions.
        sb.append("```\n").append(out.bodyShown()).append("\n```\n");
        return sb.toString();
    }

    private static String yamlScalar(String value) {
        if (value == null) return "";
        boolean needsQuote = value.isEmpty()
                || value.contains(":")
                || value.contains("#")
                || value.contains("'")
                || value.contains("\"")
                || value.startsWith(" ")
                || value.endsWith(" ")
                || value.contains("\n");
        if (!needsQuote) return value;
        return "'" + value.replace("'", "''").replace("\n", " ") + "'";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
