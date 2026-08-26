package de.mhus.vance.shared.megadodo;

import de.mhus.vance.api.kit.KitImportMode;
import de.mhus.vance.api.megadodo.MegadodoPhase;
import de.mhus.vance.api.megadodo.MegadodoRefType;
import de.mhus.vance.api.megadodo.MegadodoSeverity;
import de.mhus.vance.shared.settings.RetentionSettingCache;
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
 * infinite, {@code < 0} = do not write at all. The lookup goes through
 * {@link de.mhus.vance.shared.settings.RetentionSettingCache} — per write
 * means per row, and the cascade is uncached.
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
    private final RetentionSettingCache retentionCache;
    private final int defaultRetentionDays;

    public MegadodoService(
            MongoTemplate mongoTemplate,
            RetentionSettingCache retentionCache,
            @Value("${vance.megadodo.retention-days:90}") int defaultRetentionDays) {
        this.mongoTemplate = mongoTemplate;
        this.retentionCache = retentionCache;
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

    // ─── Project home ──────────────────────────────────────────
    //
    // Where a project *lives*. A project is owned by exactly one pod at a
    // time and moves when a pod dies, restarts, or the master rebalances —
    // and "which pod was it on when that happened" was not answerable
    // afterwards, because the only record was the current value of
    // homePodId and a log line on a pod that may be gone.
    //
    // Four rows, one per thing that can actually be observed, and the split
    // is the whole design:
    //
    //   claimed   arrival — carries where it came from AND when that lease
    //             was last renewed, so the gap is a readable duration
    //             instead of an inference from two adjacent rows
    //   released  the holder let go on purpose (clean shutdown)
    //   lost      the holder was still running and the lease went away
    //             anyway — GC pause, Mongo hiccup, master rebalance
    //   homeless  nobody holds it and the master could not place it
    //
    // Together they close the hole a single arrival row leaves: "arrived on
    // B" tells you A is done, but not that A left cleanly, not that A is
    // still running without it, and above all not when a project has *no*
    // home at all — which produces no arrival row precisely because nothing
    // took it over.
    //
    // What still cannot be observed from the losing side: a pod killed
    // outright. It runs no shutdown hook and no reconcile tick. That gap is
    // what `fromLastSeen` on the arrival row is for.
    //
    // traceId = the project name, like the other project rows: the point
    // here is that a project's residencies read as one history.

    /**
     * A project is now owned by this pod.
     *
     * <p>Emitted on the <b>transition</b> only. Claiming is idempotent and
     * doubles as a lease refresh, so a row per call would be a row every
     * time anybody touched the project.
     *
     * @param address      {@code ip:port} of the pod taking it over — the
     *                     operator's question is "which machine", and a pod
     *                     id does not answer it once the pod is gone
     * @param fromNode     the node that held it before, or {@code null} when
     *                     nobody did — this is what closes the previous
     *                     residency in the history
     * @param fromLastSeen when that previous lease was last renewed. Two
     *                     claims in a row otherwise only say "something
     *                     happened in between"; with this they say how long
     *                     the project was adrift and therefore whether the
     *                     handover was orderly or a failure
     */
    public void projectHomeClaimed(
            String tenantId,
            String projectName,
            String node,
            String podId,
            String address,
            @Nullable String fromNode,
            @Nullable Instant fromLastSeen) {
        record(builder(tenantId, /*projectId*/ null, "project.home", projectName)
                .phase(MegadodoPhase.SINGLE)
                .outcome("success")
                .refType(MegadodoRefType.PROJECT)
                .refId(projectName)
                // All of it in the message, like every other emitter here.
                // The document has a `details` map, but nothing reads it —
                // the first structured use of a field no view renders would
                // be a channel that looks like it works.
                .message("Project '" + projectName + "' now runs on " + node
                        + " (" + address + ", pod " + podId + ")"
                        + (fromNode == null
                                ? ", previously unowned"
                                : ", taken over from " + fromNode
                                        + (fromLastSeen == null
                                                ? " (never renewed)"
                                                : " (last renewed " + fromLastSeen + ")"))));
    }

    /**
     * A project's pod gave the lease up on purpose — clean shutdown.
     *
     * <p>The project has no home from here until something claims it, and
     * saying so is the point: an arrival row alone cannot express "left,
     * and nobody took over".
     */
    public void projectHomeReleased(
            String tenantId,
            String projectName,
            String node,
            String podId,
            String address) {
        record(builder(tenantId, /*projectId*/ null, "project.home", projectName)
                .phase(MegadodoPhase.SINGLE)
                .severity(MegadodoSeverity.WARN)
                .outcome("success")
                .refType(MegadodoRefType.PROJECT)
                .refId(projectName)
                .message("Project '" + projectName + "' released by " + node
                        + " (" + address + ", pod " + podId + ") on shutdown"
                        + " — no home until something claims it"));
    }

    /**
     * The lease went away while the holder was still running.
     *
     * <p>A different event from {@link #projectHomeReleased}, and the
     * difference is the diagnosis: releasing is orderly, losing means this
     * pod stopped renewing for long enough that the lease expired — a GC
     * pause, a Mongo outage, a paused JVM — or the master moved the project
     * elsewhere. The pod noticed by reconciling its own renewal count, so
     * this is the one involuntary departure that has a witness.
     */
    public void projectHomeLost(
            String tenantId,
            String projectName,
            String node,
            String podId,
            String address) {
        record(builder(tenantId, /*projectId*/ null, "project.home", projectName)
                .phase(MegadodoPhase.SINGLE)
                .severity(MegadodoSeverity.ERROR)
                .outcome("failure")
                .refType(MegadodoRefType.PROJECT)
                .refId(projectName)
                .message("Project '" + projectName + "' lost its lease while running on "
                        + node + " (" + address + ", pod " + podId + ")"
                        + " — local state unloaded"));
    }

    /**
     * Nobody owns this project and the master could not give it a home.
     *
     * <p>The state an arrival row can never describe, because there is no
     * arrival. Repeats once per distributor round for as long as it lasts,
     * and that is deliberate: unlike a tool going down, which is a
     * transition, this is an ongoing incident — every round is another
     * round in which a project that wants to run did not.
     *
     * @param lastNode  where it ran before, or {@code null} if it never did
     * @param lastSeen  when that lease was last renewed
     * @param reason    why placement failed — capacity, or the bring itself
     */
    public void projectHomeless(
            String tenantId,
            String projectName,
            @Nullable String lastNode,
            @Nullable Instant lastSeen,
            String reason) {
        record(builder(tenantId, /*projectId*/ null, "project.home", projectName)
                .phase(MegadodoPhase.SINGLE)
                .severity(MegadodoSeverity.ERROR)
                .outcome("failure")
                .refType(MegadodoRefType.PROJECT)
                .refId(projectName)
                .message("Project '" + projectName + "' has no home and could not be placed"
                        + (lastNode == null
                                ? ""
                                : " — last ran on " + lastNode
                                        + (lastSeen == null ? "" : " until " + lastSeen))
                        + suffix(reason)));
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

    // ─── Settings ──────────────────────────────────────────────

    /**
     * A setting changed. "Why does it behave differently since yesterday?"
     * is the most common question asked of a log like this, and the answer
     * is usually a setting.
     *
     * <p>The <b>value is never recorded</b> — not even for plain string
     * types. A key that looks harmless today holds a token tomorrow, and a
     * feed row is far easier to read than the settings collection. Who
     * changed what, and where, is enough to go look.
     *
     * @param projectId the project this row belongs to, or {@code null} for
     *                  a tenant- or user-scoped setting. Resolved by the
     *                  caller — the scope vocabulary belongs to the settings
     *                  subsystem, not here
     * @param scope     {@code tenant} | {@code project} | {@code user} —
     *                  shown to the reader, not interpreted
     * @param scopeId   the tenant / project / user it belongs to
     * @param encrypted whether the value is of a protected type; makes the
     *                  row a {@link MegadodoSeverity#WARN} because someone
     *                  changed a credential
     */
    public void settingChanged(
            String tenantId,
            @Nullable String projectId,
            String scope,
            String scopeId,
            String key,
            boolean encrypted,
            @Nullable String actor) {
        record(builder(tenantId, projectId, "setting.change", scope + ":" + scopeId + ":" + key)
                .phase(MegadodoPhase.SINGLE)
                .severity(encrypted ? MegadodoSeverity.WARN : MegadodoSeverity.INFO)
                .outcome("success")
                .actor(actor)
                .message("Setting '" + key + "' changed on " + scope + " '" + scopeId + "'"
                        + (encrypted ? " (encrypted value)" : "")));
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

    // ─── Trillian ──────────────────────────────────────────────

    /**
     * A Trillian user-loop woke itself up because its self-check found
     * something worth a turn.
     *
     * <p>One {@link MegadodoPhase#SINGLE} row, like an inbound event
     * trigger: a wakeup is a point in time. What follows from it is the
     * loop's own work, and that already shows up as whatever it does.
     *
     * <p><b>Only the wakeups that happened.</b> A due self-check that
     * finds nothing re-arms without running a turn, and that is the common
     * round — hourly, per loop, forever. A row for it would bury the ones
     * that mean something.
     *
     * @param wakeupId the id the self-check command already carries; the
     *                 trace of this one wakeup
     * @param reasons  one line per finding — this is the whole point of
     *                 the row: why it woke, not that it woke
     */
    public void trillianWokeUp(
            String tenantId,
            String projectId,
            String loopProcessId,
            @Nullable String trillianName,
            String wakeupId,
            List<String> reasons) {
        record(builder(tenantId, projectId, "trillian.wakeup", wakeupId)
                .phase(MegadodoPhase.SINGLE)
                .outcome("success")
                .actor(trillianName)
                .refType(MegadodoRefType.PROCESS)
                .refId(loopProcessId)
                .message("Trillian self-check woke up on " + reasons.size() + " finding(s)"
                        + suffix(String.join("; ", reasons))));
    }

    // ─── Kit lifecycle ─────────────────────────────────────────
    //
    // A kit is the one thing that installs *software* into a project:
    // documents, recipes, tool definitions, credentials. Whether that
    // happened, and whether all of it happened, is what the owner of the
    // project needs to be able to look up afterwards — and over the
    // provisioning path it happens with nobody watching at all.
    //
    // Emitted from KitService rather than from any one caller, so the
    // admin REST surface, the LLM tools, project-create and provisioning
    // all produce the same rows. Until they existed, the only trace of a
    // provisioning round was a line in the log of whichever pod happened
    // to own the project.
    //
    // traceId = one operation. The UI folds rows by it, so an id that
    // outlived the operation would collapse a kit's whole history into
    // a single entry.

    /**
     * A kit was installed, updated, or applied.
     *
     * @param mode     the import mode, which supplies the verb in the message
     * @param heldBack one line per thing the operation did <b>not</b> write;
     *                 empty for a clean run
     */
    public void kitImported(
            String tenantId,
            String projectId,
            KitImportMode mode,
            String kitName,
            @Nullable String sourceUrl,
            @Nullable String actor,
            List<String> heldBack,
            String traceId) {
        boolean incomplete = heldBack != null && !heldBack.isEmpty();
        // Incomplete is a different row rather than a detail on a success
        // one, because it reads differently: an operation that reports
        // success while a credential it was supposed to deliver is missing
        // looks exactly like a complete one, and the first symptom is
        // whatever the kit configured failing at its first call, days later.
        record(builder(tenantId, projectId, "kit.lifecycle", traceId)
                .phase(MegadodoPhase.SINGLE)
                .severity(incomplete ? MegadodoSeverity.WARN : MegadodoSeverity.INFO)
                .outcome(incomplete ? "incomplete" : "success")
                .actor(actor)
                .refType(MegadodoRefType.KIT)
                .refId(kitName)
                .message("Kit '" + kitName + "' " + verbFor(mode)
                        + (sourceUrl == null || sourceUrl.isBlank() ? "" : " from " + sourceUrl)
                        + (incomplete
                                ? " — incompletely" + suffix(String.join("; ", heldBack))
                                : "")));
    }

    /** A kit was installed, updated or applied — and did not work out. */
    public void kitImportFailed(
            String tenantId,
            String projectId,
            KitImportMode mode,
            String subject,
            @Nullable String reason,
            @Nullable String actor,
            String traceId) {
        record(builder(tenantId, projectId, "kit.lifecycle", traceId)
                .phase(MegadodoPhase.SINGLE)
                .severity(MegadodoSeverity.ERROR)
                .outcome("failure")
                .actor(actor)
                .refType(MegadodoRefType.KIT)
                .refId(subject)
                .message("Kit '" + subject + "' could not be " + verbFor(mode)
                        + suffix(reason)));
    }

    /**
     * A kit's install record was removed.
     *
     * @param prune whether its artefacts went with it — the difference
     *              between forgetting a kit and deleting what it wrote
     */
    public void kitUninstalled(
            String tenantId,
            String projectId,
            String kitId,
            boolean prune,
            @Nullable String actor,
            String traceId) {
        record(builder(tenantId, projectId, "kit.lifecycle", traceId)
                .phase(MegadodoPhase.SINGLE)
                // Not an error, but it removes things — WARN so it stands out
                // in a feed somebody is scanning for what changed.
                .severity(MegadodoSeverity.WARN)
                .outcome("success")
                .actor(actor)
                .refType(MegadodoRefType.KIT)
                .refId(kitId)
                .message("Kit '" + kitId + "' uninstalled"
                        + (prune ? " and its artefacts deleted" : " (artefacts kept)")));
    }

    /** Removing a kit did not work out. */
    public void kitUninstallFailed(
            String tenantId,
            String projectId,
            String kitId,
            @Nullable String reason,
            @Nullable String actor,
            String traceId) {
        record(builder(tenantId, projectId, "kit.lifecycle", traceId)
                .phase(MegadodoPhase.SINGLE)
                .severity(MegadodoSeverity.ERROR)
                .outcome("failure")
                .actor(actor)
                .refType(MegadodoRefType.KIT)
                .refId(kitId)
                .message("Kit '" + kitId + "' could not be uninstalled" + suffix(reason)));
    }

    /**
     * A provisioning entry could not be asked at all.
     *
     * <p>Its own action, separate from {@code kit.lifecycle}: this fires
     * <em>before</em> any kit has a name — an unreachable host, an
     * unreadable provisioning document — so there is no kit operation to
     * attach it to. The failures of the operations it does start are
     * reported by {@link #kitImportFailed} like anyone else's.
     */
    public void kitProvisioningFailed(
            String tenantId,
            String projectId,
            String subject,
            @Nullable String reason,
            String traceId) {
        record(builder(tenantId, projectId, "kit.provisioning", traceId)
                .phase(MegadodoPhase.SINGLE)
                .severity(MegadodoSeverity.ERROR)
                .outcome("failure")
                .refType(MegadodoRefType.KIT)
                .refId(subject)
                .message("Provisioning of '" + subject + "' failed" + suffix(reason)));
    }

    /** Past tense of what a mode does, for the message. */
    private static String verbFor(KitImportMode mode) {
        return switch (mode) {
            case INSTALL -> "installed";
            case UPDATE -> "updated";
            case APPLY -> "applied";
        };
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

    /**
     * Every row of one operation, oldest first — the expanded trace view.
     *
     * <p><b>{@code projectId} is part of the query, not decoration.</b> A
     * trace id is a foreign id reused as a correlation key — a session id, a
     * run's correlation id, and for {@code setting.change} literally
     * {@code scope:scopeId:key}, which anyone can enumerate. Reading tenant-wide
     * while the caller was only authorized against one project would hand a
     * project admin the feed of every other project of the tenant. So the scope
     * that was checked has to be the scope that is read.
     *
     * @param projectId the project the caller holds {@code ADMIN} on — rows are
     *                  restricted to it. {@code null} means the caller passed
     *                  the tenant gate and sees the whole tenant, which is the
     *                  only way to reach the tenant-wide rows
     *                  ({@code projectId == null}: user created, project
     *                  created — they belong to no project scope)
     */
    public List<MegadodoEventDocument> byTrace(
            String tenantId, @Nullable String projectId, String traceId) {
        Criteria c = Criteria.where("tenantId").is(tenantId).and("traceId").is(traceId);
        if (projectId != null && !projectId.isBlank()) {
            c = c.and("projectId").is(projectId);
        }
        Query q = new Query(c)
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
        // Through the cache, not the cascade: this runs on every single feed
        // row, and the cascade is three uncached Mongo reads for a number that
        // changes approximately never. See RetentionSettingCache.
        int days = retentionCache.days(
                tenantId, projectId, SETTING_RETENTION_DAYS, defaultRetentionDays);
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
