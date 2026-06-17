package de.mhus.vance.brain.ursascheduler;

import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.api.eventlog.EventType;
import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.api.ursascheduler.OverlapPolicy;
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
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.inbox.InboxItemService;
import de.mhus.vance.shared.ursascheduler.ResolvedUrsaScheduler;
import de.mhus.vance.shared.ursascheduler.UrsaSchedulerLoader;
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
import org.springframework.beans.factory.annotation.Value;
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
public class UrsaSchedulerService {

    private final UrsaSchedulerLoader loader;
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
    /** LLM-facing materialised log of every run — see {@link SchedulerLogService}. */
    private final SchedulerLogService schedulerLogService;
    /** For auto-disable notifications — see {@link #autoDisableScheduler}. */
    private final InboxItemService inboxItemService;

    /**
     * When {@code false}, the seconds-field of a 6-field cron expression
     * is clamped to {@code "0"} at registration time — see
     * {@link #clampCronSecondsIfDisallowed}. Default {@code false}, so
     * sub-minute crons (e.g. every-five-seconds expressions) get
     * normalised to once-per-minute. Operators can opt back in via the
     * {@code vance.scheduler.allow-seconds} property in
     * {@code application.yml}.
     *
     * <p>Field injection (not constructor) so the Lombok-generated
     * required-args constructor stays untouched; the flag is read on
     * every {@link #registerCron} call which always runs after Spring's
     * field-injection completes.
     */
    @Value("${vance.scheduler.allow-seconds:false}")
    private boolean allowSeconds;

    /** Counter for scheduler fires. Tags: {@code scheduler}, {@code outcome}. */
    private static final String METRIC_FIRES = "vance.ursascheduler.fires";

    /** Timer for successful spawn latency. Tag: {@code scheduler}. */
    private static final String METRIC_SPAWN_DURATION = "vance.ursascheduler.spawn.duration";

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
        List<ResolvedUrsaScheduler> entries = loader.listAll(tenantId, projectId);
        int ok = 0;
        for (ResolvedUrsaScheduler entry : entries) {
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
        Optional<ResolvedUrsaScheduler> reloaded;
        try {
            reloaded = loader.load(tenantId, projectId, name);
        } catch (UrsaSchedulerLoader.SchedulerParseException ex) {
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
    public List<ResolvedUrsaScheduler> listRegistered(String tenantId, String projectId) {
        List<ResolvedUrsaScheduler> out = new ArrayList<>();
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
        ResolvedUrsaScheduler cfg = reg.config;
        if (cfg.isOneShot()) {
            if (!cfg.enabled()) return null;
            // Once a STARTED event has been recorded, the one-shot is
            // considered consumed regardless of what the YAML still says.
            boolean fired = eventLogService.findLatest(
                    reg.tenantId,
                    UrsaSchedulerSourceKeys.sourceFor(cfg.name()),
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

    private boolean registerOne(String tenantId, String projectId, ResolvedUrsaScheduler config) {
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
            String tenantId, String projectId, ResolvedUrsaScheduler config, ZoneId zone) {
        String cron = config.cron();
        if (cron == null || !CronExpression.isValidExpression(cron)) {
            log.warn("Scheduler '{}/{}/{}' has invalid cron '{}' — registration skipped",
                    tenantId, projectId, config.name(), cron);
            return false;
        }
        String effectiveCron = clampCronSecondsIfDisallowed(cron, allowSeconds);
        if (!effectiveCron.equals(cron)) {
            log.warn("Scheduler '{}/{}/{}' seconds-field clamped to 0 — cron '{}' → '{}' "
                            + "(set 'vance.scheduler.allow-seconds: true' to keep sub-minute precision)",
                    tenantId, projectId, config.name(), cron, effectiveCron);
        }
        Registration reg = new Registration(tenantId, projectId, config, zone, null);
        CronTrigger trigger = new CronTrigger(effectiveCron, java.util.TimeZone.getTimeZone(zone));
        ScheduledFuture<?> future = taskScheduler.schedule(() -> safeFire(reg), trigger);
        reg.future = future;
        registry.put(registryKey(tenantId, projectId, config.name()), reg);
        log.info("Scheduler '{}/{}/{}' registered cron='{}' tz={} runAs='{}'",
                tenantId, projectId, config.name(),
                effectiveCron, zone, config.effectiveRunAs());
        return true;
    }

    /**
     * Optionally clamp the seconds field of a 6-field cron to {@code "0"}.
     * Input is assumed valid (the loader auto-upgrades 5-field to
     * 6-field). Returns the input unchanged when {@code allowSeconds} is
     * true, when the cron isn't 6-field, or when the seconds field is
     * already {@code "0"}. The original cron stays in the YAML; only the
     * runtime trigger sees the clamped value.
     */
    static String clampCronSecondsIfDisallowed(String cron, boolean allowSeconds) {
        if (allowSeconds) return cron;
        String[] fields = cron.trim().split("\\s+");
        if (fields.length != 6) return cron;
        if ("0".equals(fields[0])) return cron;
        fields[0] = "0";
        return String.join(" ", fields);
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
            String tenantId, String projectId, ResolvedUrsaScheduler config, ZoneId zone) {
        Instant at = config.at();
        if (at == null) {
            log.warn("Scheduler '{}/{}/{}' missing 'at' on one-shot — registration skipped",
                    tenantId, projectId, config.name());
            return false;
        }
        boolean alreadyFired = eventLogService.findLatest(
                tenantId,
                UrsaSchedulerSourceKeys.sourceFor(config.name()),
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
            fire(reg, "cron", "run_" + UUID.randomUUID(), Instant.now());
        } catch (RuntimeException ex) {
            log.error("Scheduler '{}/{}/{}' tick failed: {}",
                    reg.tenantId, reg.projectId, reg.config.name(), ex.toString(), ex);
        }
    }

    /**
     * Trigger a registered scheduler immediately, bypassing the cron
     * schedule. Used by the {@code ursascheduler_fire} agent tool and
     * the {@code POST /scheduler/{name}/fire} REST endpoint so the
     * model and operators can verify a scheduler end-to-end without
     * waiting for its next natural tick.
     *
     * <p>Goes through the exact same code path as a cron tick —
     * overlap policy applies, event-log + scheduler-log + metrics fire
     * identically — only the {@code trigger} marker on the
     * scheduler-log document differs ({@code manual} vs. {@code cron}).
     *
     * @return the {@code correlationId} of the freshly created run plus
     *         the {@code firedAt} stamp used for path computation so the
     *         caller can locate the matching scheduler-log document
     *         deterministically — see {@link FireOutcome}.
     * @throws IllegalArgumentException when no scheduler with that name
     *         is currently registered for {@code (tenantId, projectId)}.
     */
    public FireOutcome fireNow(String tenantId, String projectId, String name) {
        Registration reg = registry.get(registryKey(tenantId, projectId, name));
        if (reg == null) {
            throw new IllegalArgumentException(
                    "Scheduler '" + name + "' is not registered for project '"
                            + tenantId + "/" + projectId + "' (check spelling and that the document is enabled).");
        }
        // The cron path runs on a TaskScheduler thread; for a manual
        // fire we still want the same isolation — submit on the
        // scheduler pool and return the correlationId synchronously so
        // the caller can immediately read the (pending) log document.
        //
        // firedAt is captured *here* and passed through to fire() so
        // the path the caller computes via SchedulerLogService.pathFor
        // matches what the runnable actually writes. Without this, the
        // runnable's own Instant.now() inside the TaskScheduler thread
        // can fall on a different second, causing the LLM to read a
        // path that doesn't exist (see ticket mhus/vance#1).
        String correlationId = "run_" + UUID.randomUUID();
        Instant firedAt = Instant.now();
        taskScheduler.schedule(() -> {
            try {
                fire(reg, "manual", correlationId, firedAt);
            } catch (RuntimeException ex) {
                log.error("Scheduler '{}/{}/{}' manual fire failed: {}",
                        reg.tenantId, reg.projectId, reg.config.name(), ex.toString(), ex);
            }
        }, firedAt);
        return new FireOutcome(correlationId, firedAt);
    }

    /**
     * Outcome of a manual fire: carries both the {@code correlationId}
     * the caller needs for tracking AND the {@code firedAt} stamp used
     * by the writer, so REST / agent-tool layers can compute the log
     * document path that will actually exist on disk.
     */
    public record FireOutcome(String correlationId, Instant firedAt) {}

    // ───────────────────────── Fire (one tick) ─────────────────────────

    private void fire(Registration reg, String trigger, String correlationId, Instant firedAt) {
        ResolvedUrsaScheduler cfg = reg.config;
        String source = UrsaSchedulerSourceKeys.sourceFor(cfg.name());
        String runAs = cfg.effectiveRunAs();
        if (runAs == null) {
            log.warn("Scheduler '{}' fired without runAs — should have been filtered at register",
                    cfg.name());
            return;
        }
        Map<String, Object> triggeredPayload = "cron".equals(trigger)
                ? null
                : Map.of("trigger", trigger);
        eventLogService.append(reg.tenantId, reg.projectId, source,
                EventType.TRIGGERED, correlationId,
                /*sessionId*/ null, /*processId*/ null, runAs, triggeredPayload);
        schedulerLogService.onTriggered(reg.tenantId, reg.projectId, cfg.name(),
                correlationId, trigger, runAs, firedAt);
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
                schedulerLogService.onSkipped(correlationId, "overlap");
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
                schedulerLogService.onSkipped(correlationId, "overlap_queued");
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
        schedulerLogService.onCancelled(correlationId, victimId);
        reg.currentProcessId = null;
    }

    private void spawn(
            Registration reg, ResolvedUrsaScheduler cfg,
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
            schedulerLogService.onFailed(correlationId, "action_build", ex.getMessage());
            countFire(cfg.name(), "failed");
            return;
        }

        // Recipe triggers need a system session up front; the executor
        // requires a TriggerContext.Sessioned for that path. Script and
        // workflow actions don't need a session — they get a Standalone
        // context. parentSessionId is kept as a local for the
        // subsequent event-log rows that thread the session id through.
        String parentSessionId = null;
        if (action instanceof TriggerAction.Recipe) {
            SessionDocument session = systemSessionResolver.resolve(
                    reg.tenantId, reg.projectId, cfg.name(), runAs);
            parentSessionId = session.getSessionId();
        }
        TriggerContext context = parentSessionId != null
                ? TriggerContext.sessioned(
                        reg.tenantId, reg.projectId, runAs, correlationId, source,
                        parentSessionId, /*parentProcessId*/ null)
                : TriggerContext.standalone(
                        reg.tenantId, reg.projectId, runAs, correlationId, source,
                        /*parentProcessId*/ null);

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
            schedulerLogService.onFailed(correlationId, "dispatch", ex.getMessage());
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
            schedulerLogService.onFailed(correlationId, "execute", result.errorMessage());
            countFire(cfg.name(), "failed");

            // Recipe-resolution failures (typically a hallucinated or
            // deleted recipe-name in the YAML) would otherwise repeat at
            // every cron tick. Disable the scheduler and notify the
            // runAs user via the inbox — they need to fix the recipe
            // reference before re-enabling. See specification/scheduler.md.
            if (isRecipeResolutionFailure(result.errorMessage())) {
                autoDisableScheduler(reg, correlationId,
                        result.errorMessage() == null ? "recipe missing" : result.errorMessage());
            }
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
        String startedDetails = startedPayload.isEmpty()
                ? null
                : startedPayload.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .reduce((a, b) -> a + " " + b).orElse(null);
        schedulerLogService.onStarted(correlationId, parentSessionId, spawnedProcessId, startedDetails);
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
            schedulerLogService.onTerminated(correlationId, "completed", Instant.now());
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

    // ───────────────────────── Auto-disable on recipe-miss ─────────────────────────

    /**
     * Detects the two error-message shapes produced by
     * {@code SpawnActionExecutor} when the recipe-name doesn't resolve:
     * the prefixed {@code "resolution: …"} and the bare
     * {@code "unknown recipe '…'"}. Non-recipe failures (engine
     * resolution, process-create) are left to surface as transient
     * errors — only recipe-misses get the auto-disable treatment because
     * they're guaranteed to repeat at every tick.
     */
    private static boolean isRecipeResolutionFailure(@Nullable String error) {
        if (error == null) return false;
        return error.startsWith("resolution:")
                || error.startsWith("unknown recipe ");
    }

    /**
     * Flip the scheduler's YAML to {@code enabled: false}, re-register
     * (which turns the cron trigger off), and create an inbox item for
     * the run-as user. Idempotent: a second failure after disable is
     * caught at register-time (where {@code enabled=false} short-circuits
     * before {@code registerCron}) and won't reach this method.
     */
    private void autoDisableScheduler(
            Registration reg, String correlationId, String reason) {
        String tenantId = reg.tenantId;
        String projectId = reg.projectId;
        String name = reg.config.name();
        String docId = reg.config.documentId();

        if (docId == null) {
            // Cascade-resolved entries without a per-project document
            // can't be edited from here — the project owner would need
            // to override at their tier. Still notify the run-as user.
            log.warn("Scheduler '{}/{}/{}' auto-disable skipped: cascade entry, no documentId. Reason: {}",
                    tenantId, projectId, name, reason);
            notifyAutoDisabled(reg, correlationId, reason, /*persisted=*/false);
            return;
        }

        try {
            String mutated = disableInYaml(reg.config.yaml());
            documentService.update(docId,
                    /*newTitle*/ null, /*newTags*/ null,
                    /*newInlineText*/ mutated, /*newPath*/ null);
            refreshOne(tenantId, projectId, name);
            log.warn("Scheduler '{}/{}/{}' auto-disabled after recipe-resolution failure: {}",
                    tenantId, projectId, name, reason);
            notifyAutoDisabled(reg, correlationId, reason, /*persisted=*/true);
        } catch (RuntimeException ex) {
            log.error("Scheduler '{}/{}/{}' auto-disable failed: {}",
                    tenantId, projectId, name, ex.toString(), ex);
        }
    }

    /**
     * In-place edit: replace the first {@code enabled:} line with
     * {@code enabled: false}, or append the field if missing. Keeps the
     * rest of the YAML — comments, ordering, formatting — untouched
     * (a full SnakeYAML round-trip would normalise away author intent).
     */
    static String disableInYaml(String yaml) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "^(\\s*enabled\\s*:\\s*)(true|false)(\\s*)$",
                java.util.regex.Pattern.MULTILINE);
        java.util.regex.Matcher m = p.matcher(yaml);
        if (m.find()) {
            return m.replaceFirst("$1false$3");
        }
        String suffix = yaml.endsWith("\n") ? "" : "\n";
        return yaml + suffix + "enabled: false\n";
    }

    /**
     * Inbox item announcing the auto-disable. Assigned to the scheduler's
     * effective run-as user (the human or service-account on whose behalf
     * the run was happening); they're the right person to decide whether
     * to fix the recipe and re-enable. {@code OUTPUT_TEXT} with
     * {@code requiresAction=true} so it surfaces in the assignee's inbox
     * but doesn't block the originating process (there is none).
     */
    private void notifyAutoDisabled(
            Registration reg, String correlationId, String reason, boolean persisted) {
        String assignee = reg.config.effectiveRunAs();
        if (assignee == null || assignee.isBlank()) {
            log.warn("Scheduler '{}/{}/{}' auto-disable: no runAs to notify",
                    reg.tenantId, reg.projectId, reg.config.name());
            return;
        }
        String logPath = SchedulerLogService.pathFor(
                reg.config.name(), Instant.now(), correlationId);
        StringBuilder body = new StringBuilder();
        body.append("The scheduler `").append(reg.config.name())
                .append("` was ");
        body.append(persisted ? "automatically disabled" : "marked failing (cascade entry — disable not persisted)");
        body.append(" because its configured recipe could not be resolved.\n\n");
        body.append("**Error:** ").append(reason).append("\n\n");
        if (reg.config.recipe() != null) {
            body.append("**Configured recipe:** `").append(reg.config.recipe()).append("`\n\n");
        }
        body.append("**Last run log:** `").append(logPath).append("`\n\n");
        body.append("To fix:\n");
        body.append("1. Create the missing recipe under `_vance/recipes/<name>.yaml`, "
                + "or update the scheduler's `recipe:` field to an existing recipe name.\n");
        body.append("2. Set `enabled: true` in `_vance/scheduler/")
                .append(reg.config.name()).append(".yaml` to re-arm the schedule.\n");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schedulerName", reg.config.name());
        payload.put("projectId", reg.projectId);
        payload.put("correlationId", correlationId);
        payload.put("reason", reason);
        if (reg.config.recipe() != null) {
            payload.put("recipe", reg.config.recipe());
        }
        payload.put("logPath", logPath);
        payload.put("autoDisabled", persisted);

        try {
            inboxItemService.create(InboxItemDocument.builder()
                    .tenantId(reg.tenantId)
                    .originatorUserId("ursascheduler:" + reg.config.name())
                    .assignedToUserId(assignee)
                    .type(InboxItemType.OUTPUT_TEXT)
                    .criticality(Criticality.NORMAL)
                    .title("Scheduler '" + reg.config.name() + "' auto-disabled")
                    .body(body.toString())
                    .tags(List.of("scheduler", reg.config.name(), "auto-disabled", "recipe-missing"))
                    .payload(payload)
                    .requiresAction(true)
                    .build());
        } catch (RuntimeException ex) {
            log.error("Scheduler '{}/{}/{}' inbox notify failed: {}",
                    reg.tenantId, reg.projectId, reg.config.name(), ex.toString(), ex);
        }
    }

    // ───────────────────────── State holder ─────────────────────────

    /** Per-scheduler runtime state. Mutated under {@link #lock}. */
    static final class Registration {
        final String tenantId;
        final String projectId;
        final ResolvedUrsaScheduler config;
        final ZoneId zoneId;
        final Object lock = new Object();
        @Nullable volatile ScheduledFuture<?> future;
        @Nullable String currentProcessId;
        boolean pendingQueued;

        Registration(
                String tenantId, String projectId,
                ResolvedUrsaScheduler config, ZoneId zoneId,
                @Nullable ScheduledFuture<?> future) {
            this.tenantId = tenantId;
            this.projectId = projectId;
            this.config = config;
            this.zoneId = zoneId;
            this.future = future;
        }
    }
}
