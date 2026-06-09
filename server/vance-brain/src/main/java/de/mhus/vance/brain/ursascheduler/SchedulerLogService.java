package de.mhus.vance.brain.ursascheduler;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.settings.SettingService;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Writes a human-readable per-run summary document for every scheduler
 * fire — see {@code specification/scheduler.md} (Scheduler-Log Documents).
 *
 * <p>The document lives at
 * {@code _vance/logs/scheduler/<schedulerName>/<isoStamp>-<correlationId>.md}
 * inside the firing project, with markdown body and YAML front matter
 * carrying {@code kind: scheduler-log}, the run's identifiers and the
 * final outcome. Mongo's TTL index (see
 * {@link DocumentDocument#getExpiresAt()}) garbage-collects entries
 * after the configured retention (default 7d).
 *
 * <p>This is a <em>materialised view</em> on top of
 * {@link de.mhus.vance.shared.eventlog.EventLogService}: the event log
 * remains the source of truth (atomic appends, metric coupling, REST
 * surface). The document is purely the LLM-/user-readable shape that
 * the {@code document_read} tool can pick up — no new tool surface
 * needed for the model to inspect a scheduler run.
 *
 * <p>State model: an in-memory map keyed by {@code correlationId}
 * accumulates events; every update re-renders the markdown and upserts
 * the document. Terminal events ({@link #onTerminated},
 * {@link #onFailed}, {@link #onSkipped}, {@link #onCancelled}) drop the
 * entry from the map. A Brain crash between {@code STARTED} and the
 * termination listener leaves the entry stuck on {@code outcome:
 * pending}; the TTL still reaps it after the retention window, and the
 * event log retains the authoritative timeline.
 */
@Service
@Slf4j
public class SchedulerLogService {

    /** Project-relative folder all scheduler-log documents live under. */
    public static final String LOG_FOLDER_PREFIX = "_vance/logs/scheduler/";

    /** Document kind value — picked up by the LLM via document-list filters. */
    public static final String DOCUMENT_KIND = "scheduler-log";

    /**
     * Filename timestamp formatter — ISO-8601 in UTC with second
     * precision, colons replaced with dashes so document paths stay
     * filename-safe across the storage / REST / web-ui surfaces.
     */
    private static final DateTimeFormatter STAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'")
                    .withZone(ZoneOffset.UTC);

    /**
     * Setting key for the per-tenant retention override (project cascade:
     * project → {@code _tenant} → {@code application.yml} default).
     * Integer days. {@code < 1} (typically {@code 0}) disables logging
     * for the affected scope — no document is written at all. Otherwise
     * the value is clamped to {@code <= MAX_RETENTION_DAYS}.
     */
    public static final String SETTING_RETENTION_DAYS = "scheduler.log.retentionDays";

    private static final int MAX_RETENTION_DAYS = 365;

    private final DocumentService documentService;
    private final SettingService settingService;
    /** System-wide fallback when no tenant/project setting is present.
     *  May be {@code < 1} to disable logging globally by default. */
    private final int defaultRetentionDays;

    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    public SchedulerLogService(
            DocumentService documentService,
            SettingService settingService,
            @Value("${vance.scheduler.log.retention-days:7}") int defaultRetentionDays) {
        this.documentService = documentService;
        this.settingService = settingService;
        // Only clamp the upper bound — lower-bound clamping would
        // silently turn "0 = disable globally" into "log for 1 day".
        this.defaultRetentionDays = Math.min(MAX_RETENTION_DAYS, defaultRetentionDays);
        if (this.defaultRetentionDays < 1) {
            log.info("SchedulerLogService initialised — DISABLED by default (retention-days={}); "
                    + "tenants may opt back in via setting '{}'",
                    this.defaultRetentionDays, SETTING_RETENTION_DAYS);
        } else {
            log.info("SchedulerLogService initialised — default retention={}d (overridable per tenant via setting '{}'; "
                    + "set to 0 to disable)",
                    this.defaultRetentionDays, SETTING_RETENTION_DAYS);
        }
    }

    /**
     * Resolve the effective retention days for {@code (tenant, project)}
     * via the settings cascade — project beats tenant beats
     * {@code application.yml} default. A return value {@code < 1}
     * signals the caller to skip the document write entirely; positive
     * values are clamped to {@code <= MAX_RETENTION_DAYS}.
     */
    private int retentionDaysFor(String tenantId, @Nullable String projectId) {
        String raw = settingService.getStringValueCascade(
                tenantId, projectId, /*thinkProcessId*/ null, SETTING_RETENTION_DAYS);
        int days = defaultRetentionDays;
        if (raw != null && !raw.isBlank()) {
            try {
                days = Integer.parseInt(raw.trim());
            } catch (NumberFormatException ex) {
                log.warn("SchedulerLogService — setting '{}' is not an integer ('{}'), falling back to default {}d",
                        SETTING_RETENTION_DAYS, raw, defaultRetentionDays);
            }
        }
        if (days < 1) return days;          // disabled — preserve the signal
        return Math.min(MAX_RETENTION_DAYS, days);
    }

    // ─── Public hooks (called from UrsaSchedulerService / termination listener) ───

    /**
     * Record the initial {@code TRIGGERED} event. Creates the document
     * with {@code outcome: pending} so the run is visible the moment
     * the scheduler fires, even before spawn completes.
     */
    public synchronized void onTriggered(
            String tenantId, String projectId, String schedulerName,
            String correlationId, String trigger, String runAs) {
        RunState state = runs.computeIfAbsent(correlationId, id -> new RunState());
        state.tenantId = tenantId;
        state.projectId = projectId;
        state.schedulerName = schedulerName;
        state.trigger = trigger;
        state.runAs = runAs;
        state.firedAt = Instant.now();
        state.outcome = "pending";
        state.timeline.add(formatTimelineEntry(state.firedAt, "TRIGGERED", "source=" + sourceFor(schedulerName)));
        upsert(state, correlationId);
    }

    /** Process spawn succeeded — update with session/process ids. */
    public synchronized void onStarted(
            String correlationId,
            @Nullable String sessionId, @Nullable String processId,
            @Nullable String details) {
        RunState state = runs.get(correlationId);
        if (state == null) return;
        state.sessionId = sessionId;
        state.processId = processId;
        // Note we don't transition outcome to a terminal value here —
        // for recipe triggers the run is still in flight until
        // onTerminated lands; for sync scripts onTerminated is emitted
        // right after.
        String entry = "STARTED"
                + (processId != null ? " process=" + processId : "")
                + (sessionId != null ? " session=" + sessionId : "")
                + (details != null && !details.isBlank() ? " " + details : "");
        state.timeline.add(formatTimelineEntry(Instant.now(), entry, null));
        upsert(state, correlationId);
    }

    /** Spawn or executor failed before producing a process. */
    public synchronized void onFailed(
            String correlationId, String phase, @Nullable String error) {
        RunState state = runs.get(correlationId);
        if (state == null) return;
        state.outcome = "failed";
        state.error = error;
        state.completedAt = Instant.now();
        state.timeline.add(formatTimelineEntry(state.completedAt, "FAILED",
                "phase=" + phase + (error != null ? " error=" + truncate(error, 200) : "")));
        upsert(state, correlationId);
        runs.remove(correlationId);
    }

    /** Overlap policy skipped or queued this tick. */
    public synchronized void onSkipped(String correlationId, String reason) {
        RunState state = runs.get(correlationId);
        if (state == null) return;
        state.outcome = "skipped_" + reason.toLowerCase().replace(' ', '_');
        state.completedAt = Instant.now();
        state.timeline.add(formatTimelineEntry(state.completedAt, "SKIPPED", "reason=" + reason));
        upsert(state, correlationId);
        runs.remove(correlationId);
    }

    /** Overlap policy {@code CANCEL_PREVIOUS} stopped a prior run. */
    public synchronized void onCancelled(String correlationId, @Nullable String victimProcessId) {
        RunState state = runs.get(correlationId);
        if (state == null) return;
        state.timeline.add(formatTimelineEntry(Instant.now(), "CANCELLED",
                victimProcessId != null ? "victim=" + victimProcessId : null));
        upsert(state, correlationId);
        // Don't remove — the cancel is a pre-spawn step; the new spawn
        // continues and emits its own STARTED next.
    }

    /**
     * Process terminated — write final outcome and durationMs, then drop
     * the in-memory state. Called from
     * {@link UrsaSchedulerProcessTerminationListener} for recipe runs
     * and inline from {@link UrsaSchedulerService} for sync scripts.
     */
    public synchronized void onTerminated(String correlationId, String outcome, @Nullable Instant terminatedAt) {
        RunState state = runs.get(correlationId);
        if (state == null) return;
        state.outcome = outcome;
        state.completedAt = terminatedAt != null ? terminatedAt : Instant.now();
        state.timeline.add(formatTimelineEntry(state.completedAt, "TERMINATED", "outcome=" + outcome));
        upsert(state, correlationId);
        runs.remove(correlationId);
    }

    /**
     * Path the document was (or will be) written to, given the
     * correlationId. Stable input → stable output, so callers (e.g. the
     * {@code ursascheduler_fire} tool) can include it in their response
     * without consulting the service.
     */
    public static String pathFor(String schedulerName, Instant firedAt, String correlationId) {
        return LOG_FOLDER_PREFIX + schedulerName + "/"
                + STAMP_FMT.format(firedAt) + "-" + correlationId + ".md";
    }

    // ─── Internals ───

    private void upsert(RunState state, String correlationId) {
        if (state.tenantId == null || state.projectId == null
                || state.schedulerName == null || state.firedAt == null) {
            log.debug("SchedulerLogService upsert skipped — incomplete state for correlationId={}",
                    correlationId);
            return;
        }
        // Resolve retention per-write (rather than caching on the
        // RunState) so a setting change between TRIGGERED and the
        // termination update takes effect on the same run's final
        // upsert. The cost is one settings cascade lookup per upsert
        // (4 events typically); Mongo lookups are sub-ms and indexed.
        int retentionDays = retentionDaysFor(state.tenantId, state.projectId);
        if (retentionDays < 1) {
            // Logging disabled for this scope — skip the document
            // write entirely. The event-log + metrics still capture
            // the run; the document layer just stays clean.
            log.trace("SchedulerLogService — write skipped (retention<1) for '{}/{}/{}' run={}",
                    state.tenantId, state.projectId, state.schedulerName, correlationId);
            return;
        }
        String path = pathFor(state.schedulerName, state.firedAt, correlationId);
        String body = renderMarkdown(state, correlationId);
        Instant expiresAt = state.firedAt.plusSeconds(Duration.ofDays(retentionDays).toSeconds());
        try {
            documentService.upsertEphemeralText(
                    state.tenantId, state.projectId, path,
                    /*title*/ "Scheduler '" + state.schedulerName + "' — " + correlationId,
                    List.of("scheduler-log", state.schedulerName),
                    body,
                    /*createdBy*/ "ursascheduler",
                    expiresAt);
        } catch (RuntimeException ex) {
            // Log upsert never blocks the scheduler — diagnostics shouldn't
            // jeopardise the run itself. The event log retains the
            // authoritative trace if the document write fails.
            log.warn("SchedulerLogService upsert failed for '{}/{}/{}' run={}: {}",
                    state.tenantId, state.projectId, state.schedulerName, correlationId,
                    ex.toString());
        }
    }

    private String renderMarkdown(RunState state, String correlationId) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("kind: ").append(DOCUMENT_KIND).append('\n');
        sb.append("scheduler: ").append(yamlScalar(state.schedulerName)).append('\n');
        sb.append("correlationId: ").append(correlationId).append('\n');
        sb.append("trigger: ").append(state.trigger == null ? "cron" : state.trigger).append('\n');
        if (state.runAs != null)   sb.append("runAs: ").append(yamlScalar(state.runAs)).append('\n');
        if (state.sessionId != null) sb.append("sessionId: ").append(state.sessionId).append('\n');
        if (state.processId != null) sb.append("processId: ").append(state.processId).append('\n');
        sb.append("firedAt: ").append(state.firedAt).append('\n');
        if (state.completedAt != null) {
            sb.append("completedAt: ").append(state.completedAt).append('\n');
            long durationMs = state.firedAt == null
                    ? 0
                    : Duration.between(state.firedAt, state.completedAt).toMillis();
            sb.append("durationMs: ").append(durationMs).append('\n');
        }
        sb.append("outcome: ").append(state.outcome == null ? "pending" : state.outcome).append('\n');
        sb.append("---\n\n");

        sb.append("# Scheduler '").append(state.schedulerName).append("' — ").append(correlationId).append("\n\n");
        sb.append("- **Fired:** ").append(state.firedAt)
                .append(" (").append(state.trigger == null ? "cron" : state.trigger).append(")\n");
        sb.append("- **Outcome:** ").append(state.outcome == null ? "pending" : state.outcome);
        if (state.completedAt != null && state.firedAt != null) {
            long durationMs = Duration.between(state.firedAt, state.completedAt).toMillis();
            sb.append(" (").append(durationMs).append(" ms)");
        }
        sb.append('\n');
        if (state.runAs != null)    sb.append("- **RunAs:** ").append(state.runAs).append('\n');
        if (state.processId != null) sb.append("- **Process:** ").append(state.processId).append('\n');
        if (state.sessionId != null) sb.append("- **Session:** ").append(state.sessionId).append('\n');

        sb.append("\n## Timeline\n\n");
        for (String entry : state.timeline) {
            sb.append("- ").append(entry).append('\n');
        }

        if (state.error != null) {
            sb.append("\n## Error\n\n```\n").append(state.error).append("\n```\n");
        }

        return sb.toString();
    }

    /**
     * Bare-minimum YAML scalar escape — sufficient for the values we
     * actually write (scheduler names, run-as identifiers). Quotes the
     * string when it contains anything that could trip a YAML parser,
     * doubles existing single quotes, otherwise emits as-is.
     */
    private static String yamlScalar(@Nullable String value) {
        if (value == null) return "";
        boolean needsQuote = value.isEmpty()
                || value.contains(":")
                || value.contains("#")
                || value.contains("'")
                || value.contains("\"")
                || value.startsWith(" ")
                || value.endsWith(" ");
        if (!needsQuote) return value;
        return "'" + value.replace("'", "''") + "'";
    }

    private static String formatTimelineEntry(Instant at, String event, @Nullable String details) {
        StringBuilder sb = new StringBuilder();
        sb.append(at).append("  ").append(event);
        if (details != null && !details.isBlank()) sb.append("  ").append(details);
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    private static String sourceFor(String schedulerName) {
        return UrsaSchedulerSourceKeys.sourceFor(schedulerName);
    }

    // ─── Per-run state holder (in-memory only) ───

    /** Mutable scratch state per correlationId — accumulated by the hook callbacks. */
    private static final class RunState {
        @Nullable String tenantId;
        @Nullable String projectId;
        @Nullable String schedulerName;
        @Nullable String trigger;
        @Nullable String runAs;
        @Nullable String sessionId;
        @Nullable String processId;
        @Nullable Instant firedAt;
        @Nullable Instant completedAt;
        @Nullable String outcome;
        @Nullable String error;
        final List<String> timeline = new ArrayList<>();
    }
}
