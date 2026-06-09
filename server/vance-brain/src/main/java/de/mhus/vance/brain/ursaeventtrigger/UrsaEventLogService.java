package de.mhus.vance.brain.ursaeventtrigger;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.settings.SettingService;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Writes a per-trigger summary document for every UrsaEvent invocation —
 * mirror of {@code SchedulerLogService} for the events subsystem.
 *
 * <p>Each call to {@link UrsaEventService#trigger} /
 * {@link UrsaEventService#triggerAdmin} that resolves a real event
 * produces exactly one document under
 * {@code _vance/logs/events/<eventName>/<isoStamp>-<correlationId>.md}.
 * Unlike scheduler runs, events are synchronous from the trigger's
 * perspective — there is no pending → terminal transition the LLM
 * needs to observe, so we write a single completed doc instead of
 * upserting an in-flight one.
 *
 * <p>{@code not_found} responses are intentionally <b>not</b> logged:
 * arbitrary webhook spam against unknown event names would otherwise
 * pollute the document layer.
 *
 * <p>{@link DocumentDocument#getExpiresAt()} carries the TTL, set to
 * {@code firedAt + retentionDays}. Retention is resolved from the
 * settings cascade (project → {@code _tenant} → {@code application.yml}
 * default) per write, so an operator can flip the value live.
 */
@Service
@Slf4j
public class UrsaEventLogService {

    public static final String LOG_FOLDER_PREFIX = "_vance/logs/events/";
    public static final String DOCUMENT_KIND = "event-log";

    /**
     * Setting key for the per-tenant retention override (project
     * cascade). Integer days. {@code < 1} (typically {@code 0})
     * disables logging for the affected scope — no document is
     * written at all. Otherwise the value is clamped to
     * {@code <= MAX_RETENTION_DAYS}. Matches the scheduler-log key
     * naming convention so operators see one consistent pattern.
     */
    public static final String SETTING_RETENTION_DAYS = "events.log.retentionDays";

    private static final int MAX_RETENTION_DAYS = 365;

    private static final DateTimeFormatter STAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'")
                    .withZone(ZoneOffset.UTC);

    /**
     * Source marker the front-matter exposes. Mirrors the metric tag
     * {@code source} on {@code vance.ursaevents.triggers}:
     * {@link #PUBLIC} is the external webhook trigger, {@link #ADMIN}
     * the JWT-authed test-fire path.
     */
    public enum TriggerSource {
        PUBLIC, ADMIN;

        String asYamlValue() { return name().toLowerCase(); }
    }

    private final DocumentService documentService;
    private final SettingService settingService;
    private final int defaultRetentionDays;

    public UrsaEventLogService(
            DocumentService documentService,
            SettingService settingService,
            @Value("${vance.events.log.retention-days:7}") int defaultRetentionDays) {
        this.documentService = documentService;
        this.settingService = settingService;
        // Only clamp the upper bound — lower-bound clamping would
        // silently turn "0 = disable globally" into "log for 1 day".
        this.defaultRetentionDays = Math.min(MAX_RETENTION_DAYS, defaultRetentionDays);
        if (this.defaultRetentionDays < 1) {
            log.info("UrsaEventLogService initialised — DISABLED by default (retention-days={}); "
                    + "tenants may opt back in via setting '{}'",
                    this.defaultRetentionDays, SETTING_RETENTION_DAYS);
        } else {
            log.info("UrsaEventLogService initialised — default retention={}d (overridable per tenant via setting '{}'; "
                    + "set to 0 to disable)",
                    this.defaultRetentionDays, SETTING_RETENTION_DAYS);
        }
    }

    /**
     * Snapshot of one event invocation. Built by {@link UrsaEventService}
     * at the trigger boundary and passed in as a single struct so the
     * log writer doesn't need a per-event mutable state holder (events
     * are sync — no later updates).
     *
     * @param tenantId            tenant the request was routed to
     * @param projectId           project owning the event YAML
     * @param eventName           event name (without {@code .yaml}); used in the path
     * @param source              {@link TriggerSource#PUBLIC} or {@link TriggerSource#ADMIN}
     * @param httpMethod          HTTP method (public source only; {@code null} for admin)
     * @param triggeredBy         user login (admin source only; {@code null} for public)
     * @param firedAt             wall-clock start of the trigger handling
     * @param durationMs          end-to-end handling duration in ms
     * @param outcome             one of the {@code countOutcome} vocab strings
     *                            ({@code success}, {@code disabled},
     *                            {@code unauthorized}, {@code spawn_failed}, …)
     * @param targetName          recipe/workflow name (or {@code script:<path>}) on success;
     *                            {@code null} on failure
     * @param spawnedId           processId / workflowRunId on success; {@code null} otherwise
     * @param runAs               effective runAs of the event;
     *                            {@code null} when the failure happened before resolution
     * @param payloadContentType  request {@code Content-Type} (public source);
     *                            {@code null} for admin or when missing
     * @param payloadSizeBytes    payload byte length when known; {@code -1} when not measured
     * @param errorMessage        human-readable failure message; {@code null} on success
     */
    public record TriggerOutcome(
            String tenantId,
            String projectId,
            String eventName,
            TriggerSource source,
            @Nullable String httpMethod,
            @Nullable String triggeredBy,
            Instant firedAt,
            long durationMs,
            String outcome,
            @Nullable String targetName,
            @Nullable String spawnedId,
            @Nullable String runAs,
            @Nullable String payloadContentType,
            long payloadSizeBytes,
            @Nullable String errorMessage) {

        /**
         * Convenience factory that mints a fresh {@code evt_<uuid>}
         * correlationId. The id is opaque to the rest of the system —
         * we own it for log-document naming only; the event subsystem
         * has no native correlation concept (unlike the scheduler).
         */
        public static String mintCorrelationId() {
            return "evt_" + UUID.randomUUID();
        }
    }

    /**
     * Write the single log document for {@code outcome}. Idempotent on
     * {@code correlationId} — re-runs replace the body but never split
     * into two docs (uses {@link DocumentService#upsertEphemeralText}).
     *
     * <p>The write is best-effort: any underlying exception is caught
     * and logged. A failed log write must not turn a successful trigger
     * into a 5xx, and a failed trigger already has its proper error
     * surface (event-log + metrics) without needing the document.
     */
    public void record(String correlationId, TriggerOutcome out) {
        int retentionDays = retentionDaysFor(out.tenantId(), out.projectId());
        if (retentionDays < 1) {
            // Logging disabled for this scope — skip the document
            // write entirely. The event-log metric counters still
            // record the trigger; the document layer just stays clean.
            log.trace("UrsaEventLogService — write skipped (retention<1) for '{}/{}/{}' correlationId={}",
                    out.tenantId(), out.projectId(), out.eventName(), correlationId);
            return;
        }
        String path = pathFor(out.eventName(), out.firedAt(), correlationId);
        String body = renderMarkdown(correlationId, out);
        Instant expiresAt = out.firedAt().plusSeconds(Duration.ofDays(retentionDays).toSeconds());
        try {
            documentService.upsertEphemeralText(
                    out.tenantId(),
                    out.projectId(),
                    path,
                    "Event '" + out.eventName() + "' — " + correlationId,
                    List.of("event-log", out.eventName(), out.outcome()),
                    body,
                    "ursaeventtrigger",
                    expiresAt);
        } catch (RuntimeException ex) {
            log.warn("UrsaEventLogService upsert failed for '{}/{}/{}' correlationId={}: {}",
                    out.tenantId(), out.projectId(), out.eventName(), correlationId, ex.toString());
        }
    }

    /**
     * Stable path the document was (or will be) written to. Exposed so
     * the {@code triggerAdmin} response (or an LLM tool later) can echo
     * it back to the caller without consulting the service.
     */
    public static String pathFor(String eventName, Instant firedAt, String correlationId) {
        return LOG_FOLDER_PREFIX + eventName + "/"
                + STAMP_FMT.format(firedAt) + "-" + correlationId + ".md";
    }

    // ─── Internals ───

    /**
     * Same semantics as {@code SchedulerLogService.retentionDaysFor}:
     * raw-value cascade resolve, return-value {@code < 1} signals the
     * caller to skip the write, positive values are MAX-clamped.
     */
    private int retentionDaysFor(String tenantId, @Nullable String projectId) {
        String raw = settingService.getStringValueCascade(
                tenantId, projectId, /*thinkProcessId*/ null, SETTING_RETENTION_DAYS);
        int days = defaultRetentionDays;
        if (raw != null && !raw.isBlank()) {
            try {
                days = Integer.parseInt(raw.trim());
            } catch (NumberFormatException ex) {
                log.warn("UrsaEventLogService — setting '{}' is not an integer ('{}'), falling back to default {}d",
                        SETTING_RETENTION_DAYS, raw, defaultRetentionDays);
            }
        }
        if (days < 1) return days;          // disabled — preserve the signal
        return Math.min(MAX_RETENTION_DAYS, days);
    }

    private static String renderMarkdown(String correlationId, TriggerOutcome out) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("kind: ").append(DOCUMENT_KIND).append('\n');
        sb.append("event: ").append(yamlScalar(out.eventName())).append('\n');
        sb.append("correlationId: ").append(correlationId).append('\n');
        sb.append("source: ").append(out.source().asYamlValue()).append('\n');
        if (out.httpMethod() != null)   sb.append("httpMethod: ").append(out.httpMethod()).append('\n');
        if (out.triggeredBy() != null)  sb.append("triggeredBy: ").append(yamlScalar(out.triggeredBy())).append('\n');
        if (out.runAs() != null)        sb.append("runAs: ").append(yamlScalar(out.runAs())).append('\n');
        sb.append("firedAt: ").append(out.firedAt()).append('\n');
        sb.append("durationMs: ").append(out.durationMs()).append('\n');
        sb.append("outcome: ").append(out.outcome()).append('\n');
        if (out.targetName() != null)   sb.append("targetName: ").append(yamlScalar(out.targetName())).append('\n');
        if (out.spawnedId() != null)    sb.append("spawnedId: ").append(out.spawnedId()).append('\n');
        if (out.payloadContentType() != null) {
            sb.append("payloadContentType: ").append(yamlScalar(out.payloadContentType())).append('\n');
        }
        if (out.payloadSizeBytes() >= 0) {
            sb.append("payloadSizeBytes: ").append(out.payloadSizeBytes()).append('\n');
        }
        sb.append("---\n\n");

        sb.append("# Event '").append(out.eventName()).append("' — ").append(correlationId).append("\n\n");
        sb.append("- **Fired:** ").append(out.firedAt())
                .append(" (").append(out.source().asYamlValue());
        if (out.httpMethod() != null) sb.append(' ').append(out.httpMethod());
        sb.append(")\n");
        sb.append("- **Outcome:** ").append(out.outcome())
                .append(" (").append(out.durationMs()).append(" ms)\n");
        if (out.runAs() != null)       sb.append("- **RunAs:** ").append(out.runAs()).append('\n');
        if (out.targetName() != null)  sb.append("- **Target:** ").append(out.targetName()).append('\n');
        if (out.spawnedId() != null)   sb.append("- **Spawned:** ").append(out.spawnedId()).append('\n');
        if (out.payloadSizeBytes() >= 0) {
            sb.append("- **Payload:** ").append(out.payloadSizeBytes()).append(" bytes");
            if (out.payloadContentType() != null) sb.append(" (").append(out.payloadContentType()).append(')');
            sb.append('\n');
        }

        if (out.errorMessage() != null) {
            sb.append("\n## Error\n\n```\n").append(out.errorMessage()).append("\n```\n");
        }

        return sb.toString();
    }

    /** Same minimal scalar escape as the scheduler-log renderer. */
    private static String yamlScalar(String value) {
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
}
