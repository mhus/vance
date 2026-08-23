package de.mhus.vance.shared.megadodo;

import de.mhus.vance.api.megadodo.MegadodoPhase;
import de.mhus.vance.api.megadodo.MegadodoRefType;
import de.mhus.vance.api.megadodo.MegadodoSeverity;
import de.mhus.vance.shared.settings.SettingService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Megadodo — the coarse activity feed a project owner reads to see what
 * happened in their project, and above all what went wrong. See
 * {@code specification/public/megadodo-system.md}.
 *
 * <h2>Emitting</h2>
 *
 * One specialised method per event type, called directly at the place
 * where the thing happens. There is no fan-out layer and no configuration
 * list deciding what counts: the call site <em>is</em> the decision, and
 * the complete inventory of what can appear in the feed is the set of
 * public methods below.
 *
 * <p>Writes are synchronous and never throw at the caller. Diagnostics
 * must not endanger the run they describe — the same rule
 * {@code SchedulerLogService} follows for its document upsert. A Mongo
 * insert is sub-millisecond and the event rate here is orders of
 * magnitude below the audit log's, so there is no queue and no worker.
 *
 * <h2>Retention</h2>
 *
 * {@code expiresAt} is computed per write from the settings cascade and
 * reaped by Mongo's TTL monitor. Tri-state, same convention as the
 * scheduler / event / web run logs: {@code > 0} days, {@code 0} =
 * infinite, {@code < 0} = do not write at all.
 */
@Service
@Slf4j
public class MegadodoService {

    /** Per-tenant / per-project override, resolved through the cascade. */
    public static final String SETTING_RETENTION_DAYS = "megadodo.retentionDays";

    /** Upper clamp so a fat-fingered setting cannot mean "forever" by accident. */
    static final int MAX_RETENTION_DAYS = 3650;

    /** A message longer than this is cut — the detail log carries the rest. */
    static final int MAX_MESSAGE_CHARS = 1000;

    private static final Pattern CURSOR_SEPARATOR = Pattern.compile("\\|");

    private final MongoTemplate mongoTemplate;
    private final SettingService settingService;
    private final int defaultRetentionDays;

    public MegadodoService(
            MongoTemplate mongoTemplate,
            SettingService settingService,
            @Value("${vance.megadodo.retention-days:90}") int defaultRetentionDays) {
        this.mongoTemplate = mongoTemplate;
        this.settingService = settingService;
        this.defaultRetentionDays = Math.min(MAX_RETENTION_DAYS, defaultRetentionDays);
    }

    // ═════════════════════════ Emitters ═════════════════════════
    //
    // One method per event type. Producers never build a document
    // themselves — that keeps action names, severities and ref-types out
    // of the call sites and the whole feed vocabulary in this file.

    // ─── Project ───────────────────────────────────────────────

    public void projectCreated(String tenantId, String projectName, @Nullable String actor) {
        record(builder(tenantId, /*projectId*/ null, "project.lifecycle", projectName)
                .phase(MegadodoPhase.SINGLE)
                .outcome("success")
                .actor(actor)
                .refType(MegadodoRefType.PROJECT)
                .refId(projectName)
                .message("Project '" + projectName + "' created"));
    }

    public void projectClosed(String tenantId, String projectName, @Nullable String actor) {
        record(builder(tenantId, /*projectId*/ null, "project.lifecycle", projectName)
                .phase(MegadodoPhase.SINGLE)
                .severity(MegadodoSeverity.WARN)
                .outcome("success")
                .actor(actor)
                .refType(MegadodoRefType.PROJECT)
                .refId(projectName)
                .message("Project '" + projectName + "' closed"));
    }

    // ─── Session ───────────────────────────────────────────────
    //
    // traceId = sessionId: a session's life is the operation, so the
    // create row and the delete row pair up on their own.

    public void sessionCreated(
            String tenantId, String projectId, String sessionId, @Nullable String actor) {
        record(builder(tenantId, projectId, "session.lifecycle", sessionId)
                .phase(MegadodoPhase.START)
                .actor(actor)
                .refType(MegadodoRefType.SESSION)
                .refId(sessionId)
                .message("Session opened"));
    }

    public void sessionDeleted(
            String tenantId, @Nullable String projectId, String sessionId, @Nullable String actor) {
        record(builder(tenantId, projectId, "session.lifecycle", sessionId)
                .phase(MegadodoPhase.END)
                .outcome("success")
                .actor(actor)
                .refType(MegadodoRefType.SESSION)
                .refId(sessionId)
                .message("Session deleted"));
    }

    // ─── User ──────────────────────────────────────────────────
    //
    // Tenant-wide: a user does not belong to a project.

    public void userCreated(String tenantId, String userName, boolean serviceAccount) {
        record(builder(tenantId, /*projectId*/ null, "user.lifecycle", userName)
                .phase(MegadodoPhase.START)
                .refType(MegadodoRefType.USER)
                .refId(userName)
                .message((serviceAccount ? "Service account '" : "User '")
                        + userName + "' created"));
    }

    public void userDeleted(String tenantId, String userName) {
        record(builder(tenantId, /*projectId*/ null, "user.lifecycle", userName)
                .phase(MegadodoPhase.END)
                .severity(MegadodoSeverity.WARN)
                .outcome("success")
                .refType(MegadodoRefType.USER)
                .refId(userName)
                .message("User '" + userName + "' deleted"));
    }

    // ─── Tool health (Agrajag) ─────────────────────────────────

    /**
     * A tool changed availability. Only real transitions belong here —
     * repeating "still down" every few minutes would drown the feed.
     *
     * @param down    {@code true} when the tool just became unusable
     * @param details free text from the health record (classification,
     *                note) — this is what tells the reader what to fix
     */
    public void toolHealthChanged(
            String tenantId,
            @Nullable String projectId,
            String toolName,
            boolean down,
            @Nullable String details) {
        record(builder(tenantId, projectId, "tool.health", toolName)
                .phase(down ? MegadodoPhase.START : MegadodoPhase.END)
                .severity(down ? MegadodoSeverity.WARN : MegadodoSeverity.INFO)
                .outcome(down ? null : "success")
                .refType(MegadodoRefType.TOOL)
                .refId(toolName)
                .message(down
                        ? "Tool '" + toolName + "' disabled" + suffix(details)
                        : "Tool '" + toolName + "' available again" + suffix(details)));
    }

    // ─── Scheduler ─────────────────────────────────────────────
    //
    // traceId = the run's correlationId, which is also the id in the
    // detail log's filename.

    public void schedulerRunStarted(
            String tenantId,
            String projectId,
            String schedulerName,
            String runId,
            @Nullable String runAs,
            @Nullable String logPath) {
        record(builder(tenantId, projectId, "scheduler.run", runId)
                .phase(MegadodoPhase.START)
                .actor(runAs)
                .refType(MegadodoRefType.SCHEDULER)
                .refId(schedulerName)
                .logPath(logPath)
                .message("Scheduler '" + schedulerName + "' started"));
    }

    public void schedulerRunFinished(
            String tenantId,
            String projectId,
            String schedulerName,
            String runId,
            boolean success,
            @Nullable String cause,
            @Nullable String logPath) {
        record(builder(tenantId, projectId, "scheduler.run", runId)
                .phase(MegadodoPhase.END)
                .severity(success ? MegadodoSeverity.INFO : MegadodoSeverity.ERROR)
                .outcome(success ? "success" : "failure")
                .refType(MegadodoRefType.SCHEDULER)
                .refId(schedulerName)
                .logPath(logPath)
                .message(success
                        ? "Scheduler '" + schedulerName + "' finished"
                        : "Scheduler '" + schedulerName + "' failed" + suffix(cause)));
    }

    /**
     * Tick fired but nothing ran — overlap, disabled race, missing recipe.
     * Closes the trace like a finish would: the START row is already out,
     * and a trace that never ends reads as "still running".
     */
    public void schedulerRunSkipped(
            String tenantId,
            String projectId,
            String schedulerName,
            String runId,
            String reason) {
        record(builder(tenantId, projectId, "scheduler.run", runId)
                .phase(MegadodoPhase.END)
                .severity(MegadodoSeverity.WARN)
                .outcome("skipped")
                .refType(MegadodoRefType.SCHEDULER)
                .refId(schedulerName)
                .message("Scheduler '" + schedulerName + "' skipped" + suffix(reason)));
    }

    // ─── Ursa hooks ────────────────────────────────────────────

    public void hookRunStarted(
            String tenantId, String projectId, String hookName, String runId, String eventName) {
        record(builder(tenantId, projectId, "hook.run", runId)
                .phase(MegadodoPhase.START)
                .refType(MegadodoRefType.HOOK)
                .refId(hookName)
                .message("Hook '" + hookName + "' fired on " + eventName));
    }

    public void hookRunFinished(
            String tenantId,
            String projectId,
            String hookName,
            String runId,
            boolean success,
            @Nullable String cause) {
        record(builder(tenantId, projectId, "hook.run", runId)
                .phase(MegadodoPhase.END)
                .severity(success ? MegadodoSeverity.INFO : MegadodoSeverity.ERROR)
                .outcome(success ? "success" : "failure")
                .refType(MegadodoRefType.HOOK)
                .refId(hookName)
                .message(success
                        ? "Hook '" + hookName + "' finished"
                        : "Hook '" + hookName + "' failed" + suffix(cause)));
    }

    // ─── Ursa events (inbound triggers) ────────────────────────

    /**
     * An inbound event trigger ran. One {@link MegadodoPhase#SINGLE} row,
     * not a START/END pair: the trigger surface only reports once, when it
     * is over — a webhook hit is a point in time, and inventing a START
     * the emitter never saw would mean guessing.
     */
    public void eventTriggered(
            String tenantId,
            String projectId,
            String eventName,
            String runId,
            boolean success,
            @Nullable String cause,
            @Nullable String triggeredBy,
            @Nullable String logPath) {
        record(builder(tenantId, projectId, "event.trigger", runId)
                .phase(MegadodoPhase.SINGLE)
                .severity(success ? MegadodoSeverity.INFO : MegadodoSeverity.ERROR)
                .outcome(success ? "success" : "failure")
                .actor(triggeredBy)
                .refType(MegadodoRefType.EVENT)
                .refId(eventName)
                .logPath(logPath)
                .message(success
                        ? "Event '" + eventName + "' handled"
                        : "Event '" + eventName + "' failed" + suffix(cause)));
    }

    // ═════════════════════════ Reading ═════════════════════════

    /**
     * One page of the feed, newest first. Keyset paging on
     * {@code (timestamp, id)} — an offset would shift the page boundary
     * in a collection that grows while it is being read.
     */
    public MegadodoPage query(MegadodoQuery q) {
        int limit = Math.max(1, Math.min(q.limit(), 200));
        Criteria c = Criteria.where("tenantId").is(q.tenantId());

        if (q.projectId() != null && !q.projectId().isBlank()) {
            c = c.and("projectId").is(q.projectId());
        }
        List<Criteria> timeBounds = new ArrayList<>();
        if (q.from() != null) timeBounds.add(Criteria.where("timestamp").gte(q.from()));
        if (q.to() != null) timeBounds.add(Criteria.where("timestamp").lt(q.to()));
        Cursor cursor = Cursor.decode(q.cursor());
        if (cursor != null) {
            // Strictly after the last row of the previous page in
            // (timestamp DESC, id DESC) order.
            timeBounds.add(new Criteria().orOperator(
                    Criteria.where("timestamp").lt(cursor.timestamp()),
                    new Criteria().andOperator(
                            Criteria.where("timestamp").is(cursor.timestamp()),
                            Criteria.where("_id").lt(cursor.id()))));
        }
        if (!timeBounds.isEmpty()) {
            c = c.andOperator(timeBounds.toArray(new Criteria[0]));
        }
        if (q.minSeverity() != null) {
            c = c.and("severity").in(atOrAbove(q.minSeverity()));
        }
        if (q.actionPrefix() != null && !q.actionPrefix().isBlank()) {
            c = c.and("action").regex("^" + Pattern.quote(q.actionPrefix()));
        }
        if (q.refType() != null) {
            c = c.and("refType").is(q.refType());
        }
        if (q.refId() != null && !q.refId().isBlank()) {
            c = c.and("refId").is(q.refId());
        }
        if (q.actor() != null && !q.actor().isBlank()) {
            c = c.and("actor").is(q.actor());
        }
        if (q.text() != null && !q.text().isBlank()) {
            c = c.and("message").regex(Pattern.quote(q.text().trim()), "i");
        }

        Query query = new Query(c)
                .with(Sort.by(Sort.Order.desc("timestamp"), Sort.Order.desc("_id")))
                // One extra row tells us whether a next page exists without
                // a second count query.
                .limit(limit + 1);
        List<MegadodoEventDocument> rows = mongoTemplate.find(query, MegadodoEventDocument.class);

        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            MegadodoEventDocument last = rows.get(rows.size() - 1);
            next = new Cursor(last.getTimestamp(), String.valueOf(last.getId())).encode();
        }
        return new MegadodoPage(List.copyOf(rows), next);
    }

    /**
     * Recent rows about one thing, newest first — the run history of a
     * scheduler, a hook, a tool. Replaces the per-subsystem
     * {@code event_log} lookups those views used to do.
     */
    public List<MegadodoEventDocument> listForRef(
            String tenantId,
            @Nullable String projectId,
            MegadodoRefType refType,
            String refId,
            int limit) {
        return query(new MegadodoQuery(tenantId, projectId, null, null, null, null,
                refType, refId, null, null, null, limit)).items();
    }

    /**
     * Newest row about one thing — the "when did this last run?" lookup.
     * Empty when nothing was ever recorded, or when the retention window
     * has moved past it.
     */
    public Optional<MegadodoEventDocument> latestForRef(
            String tenantId,
            @Nullable String projectId,
            MegadodoRefType refType,
            String refId) {
        List<MegadodoEventDocument> rows = listForRef(tenantId, projectId, refType, refId, 1);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Every row of one operation, oldest first — the expanded trace view. */
    public List<MegadodoEventDocument> byTrace(String tenantId, String traceId) {
        Query q = new Query(Criteria.where("tenantId").is(tenantId)
                .and("traceId").is(traceId))
                .with(Sort.by(Sort.Order.asc("timestamp")))
                .limit(500);
        return mongoTemplate.find(q, MegadodoEventDocument.class);
    }

    // ═════════════════════════ Internals ═════════════════════════

    /**
     * Persist one row. Package-private on purpose: producers go through a
     * named emitter so the feed vocabulary stays in this file.
     */
    void record(MegadodoEventDocument.MegadodoEventDocumentBuilder builder) {
        MegadodoEventDocument doc = builder.build();
        int retentionDays = retentionDaysFor(doc.getTenantId(), doc.getProjectId());
        if (retentionDays < 0) {
            log.trace("Megadodo — write skipped (retention<0) for action='{}' tenant='{}'",
                    doc.getAction(), doc.getTenantId());
            return;
        }
        if (doc.getTimestamp() == null || doc.getTimestamp() == Instant.EPOCH) {
            doc.setTimestamp(Instant.now());
        }
        // retentionDays == 0 → no expiresAt, Mongo's TTL monitor skips the
        // row entirely (absent indexed field is never expired).
        if (retentionDays > 0) {
            doc.setExpiresAt(doc.getTimestamp()
                    .plusSeconds(Duration.ofDays(retentionDays).toSeconds()));
        }
        doc.setMessage(truncate(doc.getMessage()));
        try {
            mongoTemplate.insert(doc);
        } catch (RuntimeException ex) {
            // Never let the feed break what it observes.
            log.warn("Megadodo write failed for action='{}' trace='{}': {}",
                    doc.getAction(), doc.getTraceId(), ex.toString());
        }
    }

    private MegadodoEventDocument.MegadodoEventDocumentBuilder builder(
            String tenantId, @Nullable String projectId, String action, String traceId) {
        return MegadodoEventDocument.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .action(action)
                .traceId(traceId)
                .severity(MegadodoSeverity.INFO)
                .timestamp(Instant.now())
                .details(new LinkedHashMap<>());
    }

    /**
     * Effective retention for {@code (tenant, project)} — project beats
     * tenant beats {@code application.yml}. Tri-state is preserved:
     * {@code > 0} days, {@code 0} infinite, {@code < 0} disabled.
     */
    private int retentionDaysFor(String tenantId, @Nullable String projectId) {
        String raw = settingService.getStringValueCascade(
                tenantId, projectId, /*thinkProcessId*/ null, SETTING_RETENTION_DAYS);
        int days = defaultRetentionDays;
        if (raw != null && !raw.isBlank()) {
            try {
                days = Integer.parseInt(raw.trim());
            } catch (NumberFormatException ex) {
                log.warn("Megadodo — setting '{}' is not an integer ('{}'), falling back to {}d",
                        SETTING_RETENTION_DAYS, raw, defaultRetentionDays);
            }
        }
        if (days <= 0) return days;
        return Math.min(MAX_RETENTION_DAYS, days);
    }

    /** Severities at or above {@code min} — the "only failures" switch. */
    private static List<MegadodoSeverity> atOrAbove(MegadodoSeverity min) {
        List<MegadodoSeverity> out = new ArrayList<>();
        for (MegadodoSeverity s : MegadodoSeverity.values()) {
            if (s.ordinal() >= min.ordinal()) out.add(s);
        }
        return out;
    }

    private static @Nullable String truncate(@Nullable String message) {
        if (message == null || message.length() <= MAX_MESSAGE_CHARS) return message;
        return message.substring(0, MAX_MESSAGE_CHARS - 1) + "…";
    }

    /** {@code ": cause"} or empty — keeps the emitters free of null checks. */
    private static String suffix(@Nullable String detail) {
        return detail == null || detail.isBlank() ? "" : ": " + detail.trim();
    }

    /** One page plus the cursor for the next one. */
    public record MegadodoPage(List<MegadodoEventDocument> items, @Nullable String nextCursor) {}

    /**
     * Keyset position, Base64 of {@code <epochMillis>|<mongoId>}. Opaque to
     * the client — encoded so nobody starts constructing one by hand.
     */
    record Cursor(Instant timestamp, String id) {

        String encode() {
            String raw = timestamp.toEpochMilli() + "|" + id;
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        static @Nullable Cursor decode(@Nullable String encoded) {
            if (encoded == null || encoded.isBlank()) return null;
            try {
                String raw = new String(Base64.getUrlDecoder().decode(encoded),
                        java.nio.charset.StandardCharsets.UTF_8);
                String[] parts = CURSOR_SEPARATOR.split(raw, 2);
                if (parts.length != 2) return null;
                return new Cursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), parts[1]);
            } catch (RuntimeException ex) {
                // A malformed cursor means "start from the top", not an error
                // page — the client may be replaying an old link.
                return null;
            }
        }
    }
}
