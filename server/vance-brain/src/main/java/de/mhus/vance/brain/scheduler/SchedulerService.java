package de.mhus.vance.brain.scheduler;

import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.api.eventlog.EventType;
import de.mhus.vance.api.scheduler.OverlapPolicy;
import de.mhus.vance.brain.action.ActionExecutorRegistry;
import de.mhus.vance.brain.action.ActionOutcome;
import de.mhus.vance.brain.action.ActionResult;
import de.mhus.vance.brain.action.TriggerContext;
import de.mhus.vance.brain.action.TriggerKind;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.eventlog.EventLogService;
import de.mhus.vance.shared.scheduler.ResolvedScheduler;
import de.mhus.vance.shared.scheduler.SchedulerLoader;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

/**
 * Runtime owner of the scheduler subsystem — see
 * {@code specification/scheduler.md}.
 *
 * <p>Holds the in-memory map of registered cron triggers per active
 * project, fires the executor on every tick, and re-fires the pending
 * tick (overlap-{@code QUEUE}) when a previous run terminates.
 *
 * <p>This service is the single mutation point for the cron registry:
 * the project-lifecycle listener calls {@link #bootstrapProject} /
 * {@link #unloadProject} on activation/suspend; the REST and tool
 * layers call {@link #refresh} / {@link #refreshOne} after the user
 * edits a scheduler document; the process-termination listener calls
 * {@link #onProcessTerminated} for queue-policy re-fire.
 *
 * <p>Concurrency model: per-registration lock guarding the overlap
 * decision; the cron-trigger thread is short-lived (queue decision +
 * spawn submit) and never blocks on the engine itself.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SchedulerService {

    private final SchedulerLoader loader;
    private final TaskScheduler taskScheduler;
    private final SystemSessionResolver systemSessionResolver;
    private final RecipeResolver recipeResolver;
    private final ThinkProcessService thinkProcessService;
    private final LaneScheduler laneScheduler;
    private final EventLogService eventLogService;
    private final DocumentService documentService;
    private final ActionExecutorRegistry actionExecutorRegistry;
    /**
     * Lazy so the bean graph doesn't loop: {@code ThinkEngineService} →
     * tool registry → us. Mirror the trick used in {@code ProcessCreateTool}.
     */
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;
    private final ObjectProvider<EngineMessageRouter> messageRouterProvider;
    /** Lazy-resolved — only used when a scheduler entry sets {@code workflow:}. May be absent when Magrathea is disabled. */
    private final ObjectProvider<de.mhus.vance.brain.magrathea.MagratheaWorkflowService> workflowServiceProvider;
    private final de.mhus.vance.shared.metric.MetricService metricService;

    /** Counter for scheduler fires. Tags: {@code scheduler}, {@code outcome}. */
    private static final String METRIC_FIRES = "vance.scheduler.fires";

    /** Timer for successful spawn latency. Tag: {@code scheduler}. */
    private static final String METRIC_SPAWN_DURATION = "vance.scheduler.spawn.duration";

    /** Concurrent registry, keyed by {@code tenant|project|name}. */
    private final Map<String, Registration> registry = new ConcurrentHashMap<>();

    // ───────────────────────── Public lifecycle API ─────────────────────────

    /**
     * Register every scheduler visible to the project. Called from the
     * project-lifecycle listener after engines have started. Idempotent
     * — if the project is already registered, the in-memory state is
     * replaced atomically (per-scheduler cancel + re-register).
     *
     * @return number of schedulers successfully registered (excludes
     *         entries skipped due to parse / cron-validation failure)
     */
    public int bootstrapProject(String tenantId, String projectId) {
        unloadProject(tenantId, projectId);
        List<ResolvedScheduler> entries = loader.listAll(tenantId, projectId);
        int ok = 0;
        for (ResolvedScheduler entry : entries) {
            if (registerOne(tenantId, projectId, entry)) {
                ok++;
            }
        }
        log.info("Scheduler bootstrap project='{}/{}' registered {}/{} entries",
                tenantId, projectId, ok, entries.size());
        return ok;
    }

    /**
     * Cancel every registered scheduler of {@code (tenant, project)}.
     * Running processes are not touched — they keep going inside their
     * system session, and their terminal events still log normally.
     */
    public void unloadProject(String tenantId, String projectId) {
        String prefix = registryKey(tenantId, projectId, "");
        int cancelled = 0;
        for (Map.Entry<String, Registration> e : new HashMap<>(registry).entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            cancelRegistration(e.getValue(), "project-unload");
            registry.remove(e.getKey());
            cancelled++;
        }
        if (cancelled > 0) {
            log.info("Scheduler unload project='{}/{}' cancelled {} entries",
                    tenantId, projectId, cancelled);
        }
    }

    /** Full project refresh — re-runs {@link #bootstrapProject}. */
    public int refresh(String tenantId, String projectId) {
        return bootstrapProject(tenantId, projectId);
    }

    /**
     * Refresh exactly one scheduler. Cancels the prior registration (if
     * any) and re-registers from the current document. If the document
     * has been deleted, the entry is simply removed.
     *
     * @return {@code true} on a live re-registration; {@code false} when
     *         the doc is gone or the new config failed to register
     */
    public boolean refreshOne(String tenantId, String projectId, String name) {
        String key = registryKey(tenantId, projectId, name);
        Registration prior = registry.remove(key);
        if (prior != null) cancelRegistration(prior, "refresh");
        Optional<ResolvedScheduler> reloaded;
        try {
            reloaded = loader.load(tenantId, projectId, name);
        } catch (SchedulerLoader.SchedulerParseException ex) {
            log.warn("Scheduler refreshOne parse failed '{}/{}/{}': {}",
                    tenantId, projectId, name, ex.getMessage());
            return false;
        }
        if (reloaded.isEmpty()) {
            log.info("Scheduler refreshOne — '{}/{}/{}' is gone after refresh",
                    tenantId, projectId, name);
            return false;
        }
        return registerOne(tenantId, projectId, reloaded.get());
    }

    // ───────────────────────── Termination hook ─────────────────────────

    /**
     * Notify the service that a scheduler-spawned process terminated.
     * Used to re-fire pending ticks under overlap-{@code QUEUE}; for the
     * other policies it's a no-op.
     */
    public void onProcessTerminated(String tenantId, String projectId, String processId) {
        for (Registration reg : registry.values()) {
            if (!tenantId.equals(reg.tenantId) || !projectId.equals(reg.projectId)) continue;
            synchronized (reg.lock) {
                if (processId.equals(reg.currentProcessId)) {
                    reg.currentProcessId = null;
                    if (reg.pendingQueued) {
                        reg.pendingQueued = false;
                        log.info("Scheduler queued re-fire '{}' after process '{}' terminated",
                                reg.config.name(), processId);
                        // Submit on the cron thread pool — keeps the
                        // listener's synchronous path short.
                        taskScheduler.schedule(
                                () -> safeFire(reg), Instant.now());
                    }
                }
            }
        }
    }

    // ───────────────────────── Read-side helpers ─────────────────────────

    /**
     * Snapshot of every scheduler currently registered for the project,
     * regardless of source. Used by the REST list endpoint and the
     * {@code scheduler_list} agent tool.
     */
    public List<ResolvedScheduler> listRegistered(String tenantId, String projectId) {
        List<ResolvedScheduler> out = new ArrayList<>();
        for (Map.Entry<String, Registration> e : registry.entrySet()) {
            if (!e.getKey().startsWith(registryKey(tenantId, projectId, ""))) continue;
            out.add(e.getValue().config);
        }
        return out;
    }

    /**
     * Next-fire timestamp — for {@code cron:} schedulers computed live
     * from the expression, for {@code at:} one-shots the {@code at}
     * value itself (or {@code null} once consumed).
     */
    public @Nullable Instant nextFireFor(String tenantId, String projectId, String name) {
        Registration reg = registry.get(registryKey(tenantId, projectId, name));
        if (reg == null) return null;
        ResolvedScheduler cfg = reg.config;
        if (cfg.isOneShot()) {
            if (!cfg.enabled()) return null;
            // Once a STARTED event has been recorded, the one-shot is
            // considered consumed regardless of what the YAML still says.
            boolean fired = eventLogService.findLatest(
                    reg.tenantId,
                    SchedulerSourceKeys.sourceFor(cfg.name()),
                    java.util.List.of(EventType.STARTED)).isPresent();
            return fired ? null : cfg.at();
        }
        String cron = cfg.cron();
        if (cron == null) return null;
        try {
            CronExpression expr = CronExpression.parse(cron);
            return expr.next(java.time.ZonedDateTime.now(reg.zoneId)) == null
                    ? null
                    : expr.next(java.time.ZonedDateTime.now(reg.zoneId)).toInstant();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // ───────────────────────── Registration internals ─────────────────────────

    private boolean registerOne(String tenantId, String projectId, ResolvedScheduler config) {
        if (!config.enabled()) {
            log.info("Scheduler '{}/{}/{}' is disabled — registration skipped",
                    tenantId, projectId, config.name());
            // Still keep an entry so refresh detects the row exists,
            // but with future=null so no ticks fire.
            registry.put(registryKey(tenantId, projectId, config.name()),
                    new Registration(tenantId, projectId, config, ZoneId.of("UTC"), null));
            return false;
        }
        ZoneId zone;
        try {
            zone = resolveZone(config.timezone());
        } catch (RuntimeException ex) {
            log.warn("Scheduler '{}/{}/{}' has invalid timezone '{}': {} — registration skipped",
                    tenantId, projectId, config.name(), config.timezone(), ex.getMessage());
            return false;
        }
        if (config.effectiveRunAs() == null) {
            log.warn("Scheduler '{}/{}/{}' has no runAs (no `runAs:` field and document has no createdBy) — registration skipped",
                    tenantId, projectId, config.name());
            return false;
        }
        if (config.isOneShot()) {
            return registerOneShot(tenantId, projectId, config, zone);
        }
        return registerCron(tenantId, projectId, config, zone);
    }

    private boolean registerCron(
            String tenantId, String projectId, ResolvedScheduler config, ZoneId zone) {
        String cron = config.cron();
        if (cron == null || !CronExpression.isValidExpression(cron)) {
            log.warn("Scheduler '{}/{}/{}' has invalid cron '{}' — registration skipped",
                    tenantId, projectId, config.name(), cron);
            return false;
        }
        Registration reg = new Registration(tenantId, projectId, config, zone, null);
        CronTrigger trigger = new CronTrigger(cron, java.util.TimeZone.getTimeZone(zone));
        ScheduledFuture<?> future = taskScheduler.schedule(() -> safeFire(reg), trigger);
        reg.future = future;
        registry.put(registryKey(tenantId, projectId, config.name()), reg);
        log.info("Scheduler '{}/{}/{}' registered cron='{}' tz={} runAs='{}'",
                tenantId, projectId, config.name(),
                cron, zone, config.effectiveRunAs());
        return true;
    }

    /**
     * One-shot ({@code at:}) registration. The event log is the source
     * of truth for "already fired" — a brain that crashes between fire
     * and doc-disable still won't re-fire on restart. Past-due at-values
     * fire immediately (catch-up); future ones go through Spring's
     * {@code schedule(Runnable, Instant)} which is automatically a
     * one-shot. See {@code specification/scheduler.md} §10a.
     */
    private boolean registerOneShot(
            String tenantId, String projectId, ResolvedScheduler config, ZoneId zone) {
        Instant at = config.at();
        if (at == null) {
            log.warn("Scheduler '{}/{}/{}' missing 'at' on one-shot — registration skipped",
                    tenantId, projectId, config.name());
            return false;
        }
        boolean alreadyFired = eventLogService.findLatest(
                tenantId,
                SchedulerSourceKeys.sourceFor(config.name()),
                java.util.List.of(EventType.STARTED)).isPresent();
        Registration reg = new Registration(tenantId, projectId, config, zone, null);
        registry.put(registryKey(tenantId, projectId, config.name()), reg);
        if (alreadyFired) {
            // Self-heal: if a previous run completed but the trash
            // step never landed (crash between fire and move), push
            // the doc into _bin now so the next bootstrap doesn't see
            // it at the scheduler prefix anymore.
            log.info("Scheduler '{}/{}/{}' one-shot already consumed — self-healing to trash",
                    tenantId, projectId, config.name());
            trashAfterFire(reg);
            return false;
        }
        Instant now = Instant.now();
        if (!at.isAfter(now)) {
            log.info("Scheduler '{}/{}/{}' one-shot is past-due ({}); firing immediately for catch-up",
                    tenantId, projectId, config.name(), at);
            taskScheduler.schedule(() -> safeFire(reg), now);
        } else {
            ScheduledFuture<?> future = taskScheduler.schedule(() -> safeFire(reg), at);
            reg.future = future;
            log.info("Scheduler '{}/{}/{}' registered one-shot at={} tz={} runAs='{}'",
                    tenantId, projectId, config.name(),
                    at, zone, config.effectiveRunAs());
        }
        return true;
    }

    private void cancelRegistration(Registration reg, String reason) {
        ScheduledFuture<?> f = reg.future;
        if (f != null) f.cancel(false);
        log.debug("Scheduler '{}/{}/{}' cancelled — {}",
                reg.tenantId, reg.projectId, reg.config.name(), reason);
    }

    private static ZoneId resolveZone(@Nullable String tz) {
        if (tz == null || tz.isBlank()) return ZoneId.of("UTC");
        try {
            return ZoneId.of(tz);
        } catch (java.time.DateTimeException ex) {
            // ZoneRulesException is a subclass of DateTimeException — covered.
            throw new IllegalArgumentException("unknown timezone '" + tz + "'");
        }
    }

    private static String registryKey(String tenantId, String projectId, String name) {
        return tenantId + "|" + projectId + "|" + name;
    }

    /** Crash-safe wrapper around {@link #fire} for the cron-thread path. */
    private void safeFire(Registration reg) {
        try {
            fire(reg);
        } catch (RuntimeException ex) {
            log.error("Scheduler '{}/{}/{}' tick failed: {}",
                    reg.tenantId, reg.projectId, reg.config.name(), ex.toString(), ex);
        }
    }

    // ───────────────────────── Fire (one tick) ─────────────────────────

    private void fire(Registration reg) {
        ResolvedScheduler cfg = reg.config;
        String source = SchedulerSourceKeys.sourceFor(cfg.name());
        String runAs = cfg.effectiveRunAs();
        if (runAs == null) {
            log.warn("Scheduler '{}' fired without runAs — should have been filtered at register",
                    cfg.name());
            return;
        }
        String correlationId = "run_" + UUID.randomUUID();
        eventLogService.append(reg.tenantId, reg.projectId, source,
                EventType.TRIGGERED, correlationId,
                /*sessionId*/ null, /*processId*/ null, runAs, /*payload*/ null);
        countFire(cfg.name(), "triggered");

        synchronized (reg.lock) {
            if (reg.currentProcessId != null) {
                if (handleOverlap(reg, source, correlationId, runAs)) {
                    return; // SKIP/QUEUE handled, no spawn
                }
                // CANCEL_PREVIOUS — fall through after cancelling the prior run
            }
            long spawnStartNanos = System.nanoTime();
            spawn(reg, cfg, source, correlationId, runAs);
            // STARTED+FAILED counters fire inside spawn(); the
            // spawn-duration timer here covers the time from the
            // tick acknowledgement to either STARTED or FAILED.
            metricService.timer(METRIC_SPAWN_DURATION, "scheduler", cfg.name())
                    .record(java.time.Duration.ofNanos(System.nanoTime() - spawnStartNanos));
        }
    }

    /** Increments {@link #METRIC_FIRES} with one of the canonical outcomes. */
    private void countFire(String schedulerName, String outcome) {
        metricService.counter(METRIC_FIRES,
                "scheduler", schedulerName,
                "outcome", outcome).increment();
    }

    /**
     * Apply the configured overlap policy when a previous run is still
     * active. Returns {@code true} if the policy already handled the
     * tick (SKIP / QUEUE) and the caller should not spawn; {@code false}
     * when CANCEL_PREVIOUS has just stopped the prior run and the
     * caller should proceed.
     *
     * <p>Must be called while holding {@code reg.lock}.
     */
    private boolean handleOverlap(
            Registration reg, String source, String correlationId, String runAs) {
        OverlapPolicy policy = reg.config.overlap() == null
                ? OverlapPolicy.SKIP : reg.config.overlap();
        switch (policy) {
            case SKIP -> {
                eventLogService.append(reg.tenantId, reg.projectId, source,
                        EventType.SKIPPED, correlationId,
                        /*sessionId*/ null, /*processId*/ null, runAs,
                        Map.of("reason", "overlap"));
                countFire(reg.config.name(), "skipped_overlap");
                log.info("Scheduler '{}/{}/{}' tick skipped — prior run still active",
                        reg.tenantId, reg.projectId, reg.config.name());
                return true;
            }
            case QUEUE -> {
                reg.pendingQueued = true;
                eventLogService.append(reg.tenantId, reg.projectId, source,
                        EventType.SKIPPED, correlationId,
                        /*sessionId*/ null, /*processId*/ null, runAs,
                        Map.of("reason", "overlap", "queued", Boolean.TRUE));
                countFire(reg.config.name(), "queued_overlap");
                log.info("Scheduler '{}/{}/{}' tick queued — prior run still active",
                        reg.tenantId, reg.projectId, reg.config.name());
                return true;
            }
            case CANCEL_PREVIOUS -> {
                cancelPriorRun(reg, source, correlationId, runAs);
                countFire(reg.config.name(), "cancelled_previous");
                return false;
            }
        }
        return false;
    }

    private void cancelPriorRun(
            Registration reg, String source, String correlationId, String runAs) {
        String victimId = reg.currentProcessId;
        if (victimId == null) return;
        Optional<ThinkProcessDocument> victimOpt = thinkProcessService.findById(victimId);
        if (victimOpt.isEmpty()) {
            reg.currentProcessId = null;
            return;
        }
        ThinkProcessDocument victim = victimOpt.get();
        try {
            laneScheduler.submit(victim.getId(),
                    () -> {
                        thinkEngineServiceProvider.getObject().stop(victim);
                        return null;
                    }).get();
        } catch (Exception ex) {
            log.warn("Scheduler cancelPrevious failed for process '{}': {}",
                    victimId, ex.toString());
        }
        eventLogService.append(reg.tenantId, reg.projectId, source,
                EventType.CANCELLED, correlationId,
                victim.getSessionId(), victimId, runAs,
                Map.of("reason", "overlap"));
        reg.currentProcessId = null;
    }

    private void spawn(
            Registration reg, ResolvedScheduler cfg,
            String source, String correlationId, String runAs) {
        TriggerAction action;
        try {
            action = cfg.toTriggerAction();
        } catch (RuntimeException ex) {
            log.warn("Scheduler '{}/{}/{}' action build failed: {}",
                    reg.tenantId, reg.projectId, cfg.name(), ex.toString());
            eventLogService.append(reg.tenantId, reg.projectId, source,
                    EventType.FAILED, correlationId,
                    /*sessionId*/ null, /*processId*/ null, runAs,
                    Map.of("phase", "action_build", "error", ex.getMessage()));
            countFire(cfg.name(), "failed");
            return;
        }

        // Recipe triggers need a system session up front; the executor
        // is session-agnostic and the caller is expected to supply
        // {@code parentSessionId} via TriggerContext.
        String parentSessionId = null;
        if (action instanceof TriggerAction.Recipe) {
            SessionDocument session = systemSessionResolver.resolve(
                    reg.tenantId, reg.projectId, cfg.name(), runAs);
            parentSessionId = session.getSessionId();
        }
        TriggerContext context = new TriggerContext(
                reg.tenantId, reg.projectId, runAs, correlationId, source,
                parentSessionId, /*parentProcessId*/ null);

        ActionResult result;
        try {
            result = actionExecutorRegistry.execute(action, context, TriggerKind.SCHEDULER);
        } catch (RuntimeException ex) {
            log.warn("Scheduler '{}/{}/{}' executor dispatch failed: {}",
                    reg.tenantId, reg.projectId, cfg.name(), ex.toString());
            eventLogService.append(reg.tenantId, reg.projectId, source,
                    EventType.FAILED, correlationId,
                    parentSessionId, /*processId*/ null, runAs,
                    Map.of("phase", "dispatch", "error", ex.getMessage()));
            countFire(cfg.name(), "failed");
            return;
        }

        if (result.outcome().isFailure()) {
            Map<String, Object> failPayload = new LinkedHashMap<>();
            failPayload.put("phase", "execute");
            failPayload.put("outcome", result.outcome().name());
            if (result.errorMessage() != null) {
                failPayload.put("error", result.errorMessage());
            }
            eventLogService.append(reg.tenantId, reg.projectId, source,
                    EventType.FAILED, correlationId,
                    parentSessionId, /*processId*/ null, runAs, failPayload);
            countFire(cfg.name(), "failed");
            return;
        }

        // STARTED. For recipe-triggers the processId travels in the
        // dedicated event-log column so the termination listener can find
        // the run; workflow / script keep their identifiers in the
        // payload.
        Map<String, Object> startedPayload = new LinkedHashMap<>();
        String spawnedProcessId = null;
        if (action instanceof TriggerAction.Workflow w) {
            startedPayload.put("workflowName", w.workflow());
            if (result.spawnedId() != null) {
                startedPayload.put("workflowRunId", result.spawnedId());
            }
        } else if (action instanceof TriggerAction.Recipe) {
            spawnedProcessId = result.spawnedId();
            reg.currentProcessId = spawnedProcessId;
        } else if (action instanceof TriggerAction.Script s) {
            startedPayload.put("scriptSource", s.source().name());
            startedPayload.put("scriptPath", s.path());
            if (s.dirName() != null) {
                startedPayload.put("scriptDirName", s.dirName());
            }
        }
        eventLogService.append(reg.tenantId, reg.projectId, source,
                EventType.STARTED, correlationId,
                parentSessionId, spawnedProcessId, runAs,
                startedPayload.isEmpty() ? null : startedPayload);
        countFire(cfg.name(), "started");
        log.info("Scheduler '{}/{}/{}' fired {} outcome='{}' spawnedId='{}'",
                reg.tenantId, reg.projectId, cfg.name(),
                action.getClass().getSimpleName(),
                result.outcome(), result.spawnedId());

        // Script runs synchronously — emit a matching COMPLETED row so
        // operators see the lifecycle end without waiting for an
        // external listener (there is none for scripts).
        if (action instanceof TriggerAction.Script && result.outcome() == ActionOutcome.SUCCESS) {
            Map<String, Object> donePayload = new LinkedHashMap<>();
            if (result.output() != null) {
                donePayload.put("scriptOutput", result.output());
            }
            eventLogService.append(reg.tenantId, reg.projectId, source,
                    EventType.COMPLETED, correlationId,
                    parentSessionId, /*processId*/ null, runAs,
                    donePayload.isEmpty() ? null : donePayload);
            countFire(cfg.name(), "completed");
        }

        if (cfg.isOneShot()) {
            trashAfterFire(reg);
        }
    }

    // ───────────────────────── Doc cleanup ─────────────────────────

    /**
     * Move a fired one-shot's YAML into the project trash via
     * {@link DocumentService#trash} — see
     * {@code specification/scheduler.md} §10a.
     *
     * <p>Trash (not hard-delete) keeps the YAML restorable from
     * {@code _bin/} for audit and re-arming use cases, and matches the
     * convention used by all other LLM/agent-facing destructive
     * operations on documents.
     *
     * <p>No-op when the registration has no backing {@code documentId}
     * — e.g. for entries that exist only through a cascade lookup
     * without a row in this project's store. The one-shot semantics
     * still hold via the event-log check on bootstrap.
     */
    private void trashAfterFire(Registration reg) {
        String docId = reg.config.documentId();
        if (docId == null) {
            log.debug("Scheduler '{}/{}/{}' has no documentId — skipping trash step",
                    reg.tenantId, reg.projectId, reg.config.name());
            return;
        }
        try {
            documentService.trash(docId);
            log.info("Scheduler '{}/{}/{}' moved to trash after one-shot fire",
                    reg.tenantId, reg.projectId, reg.config.name());
        } catch (RuntimeException ex) {
            log.warn("Scheduler '{}/{}/{}' failed to trash document '{}': {}",
                    reg.tenantId, reg.projectId, reg.config.name(), docId, ex.toString());
        }
    }

    // ───────────────────────── State holder ─────────────────────────

    /** Per-scheduler runtime state. Mutated under {@link #lock}. */
    static final class Registration {
        final String tenantId;
        final String projectId;
        final ResolvedScheduler config;
        final ZoneId zoneId;
        final Object lock = new Object();
        @Nullable volatile ScheduledFuture<?> future;
        @Nullable String currentProcessId;
        boolean pendingQueued;

        Registration(
                String tenantId, String projectId,
                ResolvedScheduler config, ZoneId zoneId,
                @Nullable ScheduledFuture<?> future) {
            this.tenantId = tenantId;
            this.projectId = projectId;
            this.config = config;
            this.zoneId = zoneId;
            this.future = future;
        }
    }
}
