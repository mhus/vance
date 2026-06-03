package de.mhus.vance.shared.audit;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.metric.MetricService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Central audit pipeline. Accepts {@link AuditEventDto}s from producers,
 * normalises them, and fans them out to every registered
 * {@link AuditConsumer}.
 *
 * <h2>Modes</h2>
 *
 * <ul>
 *   <li>{@link AuditMode#SYNC}: caller thread dispatches inline. The
 *       producer pays the full consumer cost.</li>
 *   <li>{@link AuditMode#ASYNC}: event is enqueued onto a bounded queue;
 *       a single dedicated worker thread dispatches.</li>
 * </ul>
 *
 * <p>The service starts in {@code SYNC} so events emitted during Spring
 * bean wiring are not lost. In {@code @PostConstruct} it switches to the
 * configured mode (default ASYNC). In {@code @PreDestroy} it switches
 * back to SYNC, drains the queue, and joins the worker — guaranteeing
 * no events are dropped on graceful shutdown.
 *
 * <h2>Backpressure</h2>
 *
 * The async queue is bounded (default 10 000, see
 * {@link AuditServiceProperties#getQueueSize()}). When full, new events
 * are dropped and the {@code vance.audit.dropped} counter is incremented.
 * Audit must never block a producer.
 *
 * <h2>Consumers</h2>
 *
 * Consumers are collected from the Spring context at construction time
 * and can be added/removed at runtime via {@link #addConsumer} /
 * {@link #removeConsumer}. The list is held as a
 * {@link CopyOnWriteArrayList} so iteration during dispatch is safe.
 * If no consumer is registered, the service is a no-op — there is no
 * separate {@code NoopAuditConsumer} class; the empty list is the noop.
 */
@Service
@Slf4j
public class AuditService {

    private final AuditServiceProperties properties;
    private final MetricService metricService;

    private final CopyOnWriteArrayList<AuditConsumer> consumers = new CopyOnWriteArrayList<>();
    private final BlockingQueue<AuditEventDto> queue;
    private final Object modeLock = new Object();

    /** Read by every {@link #record} call; written only inside {@link #modeLock}. */
    private volatile AuditMode mode = AuditMode.SYNC;

    /** Worker termination flag — flipped in {@link #shutdown}. */
    private volatile boolean shuttingDown = false;

    @Nullable
    private ExecutorService worker;

    public AuditService(AuditServiceProperties properties,
                        MetricService metricService,
                        List<AuditConsumer> springConsumers) {
        this.properties = properties;
        this.metricService = metricService;
        int capacity = Math.max(1, properties.getQueueSize());
        this.queue = new LinkedBlockingQueue<>(capacity);
        this.consumers.addAll(springConsumers);
    }

    @PostConstruct
    void init() {
        AuditMode target = properties.getMode() != null ? properties.getMode() : AuditMode.ASYNC;
        setMode(target);
        log.info("AuditService initialized: mode={} queueSize={} consumers={}",
                mode, properties.getQueueSize(),
                consumers.stream().map(c -> c.getClass().getSimpleName()).toList());
    }

    @PreDestroy
    void shutdown() {
        shuttingDown = true;
        // Flip to SYNC: new records dispatch direct, the existing queue is drained
        // on this caller thread inside setMode.
        setMode(AuditMode.SYNC);
        // Join the worker — it may be mid-dispatch on an event picked up before
        // setMode acquired modeLock.
        ExecutorService w = this.worker;
        if (w != null) {
            w.shutdown();
            try {
                if (!w.awaitTermination(properties.getDrainTimeoutMs(), TimeUnit.MILLISECONDS)) {
                    log.warn("AuditService worker did not terminate within {}ms — {} events may be in flight",
                            properties.getDrainTimeoutMs(), queue.size());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            this.worker = null;
        }
        // Belt-and-suspenders: anything that slipped into the queue during the
        // mode flip is dispatched on this thread before we return.
        AuditEventDto remaining;
        int late = 0;
        while ((remaining = queue.poll()) != null) {
            dispatch(remaining);
            late++;
        }
        if (late > 0) {
            log.info("AuditService dispatched {} late events on shutdown", late);
        }
        log.info("AuditService shutdown complete");
    }

    // ─── Producer API ────────────────────────────────────────────────────

    /**
     * Submit an audit event. Returns immediately. Missing
     * {@link AuditEventDto#getTimestamp()} / {@link AuditEventDto#getSeverity()}
     * are filled in with sensible defaults.
     */
    public void record(AuditEventDto event) {
        if (event.getTimestamp() == null) {
            event.setTimestamp(Instant.now());
        }
        if (event.getSeverity() == null) {
            event.setSeverity(AuditSeverity.INFO);
        }

        metricService.counter("vance.audit.events",
                "severity", event.getSeverity().name().toLowerCase()).increment();

        if (mode == AuditMode.SYNC) {
            dispatch(event);
            return;
        }

        if (!queue.offer(event)) {
            metricService.counter("vance.audit.dropped").increment();
            log.warn("AuditService queue full (capacity={}) — dropped event action={} actor={}",
                    properties.getQueueSize(), event.getAction(), event.getActor());
        }
    }

    // ─── Specialised emitters ───────────────────────────────────────────
    //
    // One method per call site. Each builds the AuditEventDto inline and
    // forwards to record(). Producers don't construct DTOs themselves —
    // keeps action vocabulary and severity choices centralised.

    // === Auth (Brain) ===

    public void authLoginSuccess(String tenantId, String username) {
        record(AuditEventDto.builder()
                .action("auth.login")
                .severity(AuditSeverity.INFO)
                .outcome("success")
                .tenantId(tenantId)
                .actor(username)
                .target("user:" + username)
                .build());
    }

    public void authLoginFailure(String tenantId, String username, String reason) {
        record(AuditEventDto.builder()
                .action("auth.login")
                .severity(AuditSeverity.WARN)
                .outcome("failure")
                .tenantId(tenantId)
                .actor(username)
                .target("user:" + username)
                .message(reason)
                .build());
    }

    public void authTokenRefresh(String tenantId, String username) {
        record(AuditEventDto.builder()
                .action("auth.token.refresh")
                .severity(AuditSeverity.INFO)
                .outcome("success")
                .tenantId(tenantId)
                .actor(username)
                .target("user:" + username)
                .build());
    }

    public void authLogout(String tenantId, @Nullable String username) {
        record(AuditEventDto.builder()
                .action("auth.logout")
                .severity(AuditSeverity.INFO)
                .outcome("success")
                .tenantId(tenantId)
                .actor(username)
                .target(username == null ? "tenant:" + tenantId : "user:" + username)
                .build());
    }

    // === Auth (Anus shell) ===
    // Anus is single-user, no tenant context. Kept distinct from Brain
    // so a reviewer can filter on "who logged into the admin shell".

    public void anusLoginSuccess() {
        record(AuditEventDto.builder()
                .action("anus.login")
                .severity(AuditSeverity.WARN)  // admin shell access — always WARN
                .outcome("success")
                .target("shell:anus")
                .build());
    }

    public void anusLoginFailure() {
        record(AuditEventDto.builder()
                .action("anus.login")
                .severity(AuditSeverity.WARN)
                .outcome("failure")
                .target("shell:anus")
                .message("bad password")
                .build());
    }

    public void anusSudoArm() {
        record(AuditEventDto.builder()
                .action("anus.sudo.arm")
                .severity(AuditSeverity.WARN)  // unattended admin access — always WARN
                .outcome("success")
                .target("shell:anus")
                .build());
    }

    // === Settings ===

    public void settingsUpdate(
            String tenantId,
            String referenceType,
            String referenceId,
            String key,
            SettingType type) {
        // The value itself is never logged here — too easy for a STRING
        // setting to hold a token. Reviewers cross-reference with the
        // settings collection via tenantId + referenceType + referenceId + key.
        record(AuditEventDto.builder()
                .action("settings.update")
                .severity(type == SettingType.PASSWORD
                        ? AuditSeverity.WARN
                        : AuditSeverity.INFO)
                .tenantId(tenantId)
                .target("setting:" + key)
                .details(orderedMap(
                        "referenceType", referenceType,
                        "referenceId", referenceId,
                        "type", type.name()))
                .build());
    }

    public void settingsPasswordRead(
            String tenantId,
            String referenceType,
            String referenceId,
            String key) {
        // Vault decrypt — always WARN, reviewer interest is high.
        record(AuditEventDto.builder()
                .action("settings.password.read")
                .severity(AuditSeverity.WARN)
                .tenantId(tenantId)
                .target("setting:" + key)
                .details(orderedMap(
                        "referenceType", referenceType,
                        "referenceId", referenceId))
                .build());
    }

    // === Projects ===

    public void projectCreate(String tenantId, String projectName) {
        record(AuditEventDto.builder()
                .action("project.create")
                .severity(AuditSeverity.INFO)
                .outcome("success")
                .tenantId(tenantId)
                .projectId(projectName)
                .target("project:" + projectName)
                .build());
    }

    public void projectClose(String tenantId, String projectName, String closedGroupId) {
        // Project close is CRITICAL — it disposes workspace + RAG and
        // moves the project into the closed group. Not a hard delete,
        // but the closest thing the system has today.
        record(AuditEventDto.builder()
                .action("project.close")
                .severity(AuditSeverity.CRITICAL)
                .outcome("success")
                .tenantId(tenantId)
                .projectId(projectName)
                .target("project:" + projectName)
                .details(orderedMap("closedGroupId", closedGroupId))
                .build());
    }

    // === LLM ===

    /**
     * Engine-driven LLM round-trip (Arthur, Vogon, Marvin, …). Called from
     * the {@code EngineChatFactory} trace-writer lambda alongside the
     * {@code LlmTraceRecorder} DB write.
     */
    public void llmEngineCall(
            @Nullable String tenantId,
            @Nullable String projectId,
            @Nullable String sessionId,
            @Nullable String processId,
            String engine,
            @Nullable String modelAlias,
            @Nullable Integer tokensIn,
            @Nullable Integer tokensOut,
            long elapsedMs,
            boolean success,
            @Nullable String errorMessage) {
        record(AuditEventDto.builder()
                .action("llm.engine.call")
                .severity(AuditSeverity.INFO)
                .outcome(success ? "success" : "failure")
                .tenantId(tenantId)
                .projectId(projectId)
                .sessionId(sessionId)
                .target("engine:" + engine + (processId == null ? "" : ",process:" + processId))
                .message(errorMessage)
                .details(orderedMap(
                        "processId", processId,
                        "modelAlias", modelAlias,
                        "tokensIn", tokensIn,
                        "tokensOut", tokensOut,
                        "elapsedMs", elapsedMs))
                .build());
    }

    /**
     * Single-shot LLM call via {@code LightLlmService} (Discovery,
     * Title-Gen, Kit-Resolver, Recipe-Selector, Prak …). No process / no
     * session scope — recipe is the routing key.
     */
    public void llmLightCall(
            @Nullable String tenantId,
            @Nullable String projectId,
            String recipe,
            @Nullable String modelAlias,
            @Nullable Integer tokensIn,
            @Nullable Integer tokensOut,
            long elapsedMs,
            boolean success,
            @Nullable String errorMessage) {
        record(AuditEventDto.builder()
                .action("llm.light.call")
                .severity(AuditSeverity.INFO)
                .outcome(success ? "success" : "failure")
                .tenantId(tenantId)
                .projectId(projectId)
                .target("recipe:" + recipe)
                .message(errorMessage)
                .details(orderedMap(
                        "modelAlias", modelAlias,
                        "tokensIn", tokensIn,
                        "tokensOut", tokensOut,
                        "elapsedMs", elapsedMs))
                .build());
    }

    /**
     * Builds an ordered map skipping {@code null} values. Predictable
     * iteration order keeps log output stable.
     */
    private static Map<String, Object> orderedMap(@Nullable Object... keyValuePairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (keyValuePairs == null) return map;
        for (int i = 0; i + 1 < keyValuePairs.length; i += 2) {
            Object key = keyValuePairs[i];
            Object value = keyValuePairs[i + 1];
            if (key == null || value == null) continue;
            map.put(key.toString(), value);
        }
        return map;
    }

    // ─── Consumer registration ──────────────────────────────────────────

    public void addConsumer(AuditConsumer consumer) {
        consumers.addIfAbsent(consumer);
    }

    public boolean removeConsumer(AuditConsumer consumer) {
        return consumers.remove(consumer);
    }

    public List<AuditConsumer> getConsumers() {
        return List.copyOf(consumers);
    }

    // ─── Mode control ───────────────────────────────────────────────────

    public AuditMode getMode() {
        return mode;
    }

    /** Current queue depth — useful for tests and probes. */
    public int getQueueDepth() {
        return queue.size();
    }

    /**
     * Switch dispatch mode at runtime. Switching to {@link AuditMode#SYNC}
     * drains the queue on the caller thread; switching to ASYNC starts the
     * worker if it is not already running.
     */
    public void setMode(AuditMode newMode) {
        synchronized (modeLock) {
            if (newMode == AuditMode.ASYNC) {
                ensureWorkerStarted();
                if (mode != AuditMode.ASYNC) {
                    mode = AuditMode.ASYNC;
                    log.info("AuditService mode -> ASYNC");
                }
                return;
            }
            // newMode == SYNC: flip first so new producers go direct, then drain
            // whatever the worker hasn't picked up yet.
            if (mode != AuditMode.SYNC) {
                mode = AuditMode.SYNC;
                log.info("AuditService mode -> SYNC");
            }
            AuditEventDto event;
            int drained = 0;
            while ((event = queue.poll()) != null) {
                dispatch(event);
                drained++;
            }
            if (drained > 0) {
                log.info("AuditService drained {} queued events on switch to SYNC", drained);
            }
        }
    }

    // ─── Internals ───────────────────────────────────────────────────────

    private void dispatch(AuditEventDto event) {
        for (AuditConsumer consumer : consumers) {
            try {
                consumer.consume(event);
            } catch (RuntimeException e) {
                metricService.exception(consumer.getClass(), "audit.consume", e);
                log.warn("AuditConsumer {} threw on event action={}: {}",
                        consumer.getClass().getSimpleName(), event.getAction(), e.toString());
            }
        }
    }

    private void ensureWorkerStarted() {
        if (worker != null && !worker.isShutdown()) return;
        ExecutorService w = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "vance-audit-worker");
            t.setDaemon(true);
            return t;
        });
        w.submit(this::workerLoop);
        this.worker = w;
    }

    private void workerLoop() {
        log.debug("AuditService worker started");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                AuditEventDto event = queue.poll(500, TimeUnit.MILLISECONDS);
                if (event != null) {
                    dispatch(event);
                } else if (shuttingDown) {
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                metricService.exception(getClass(), "audit.workerLoop", e);
                log.warn("AuditService worker loop error: {}", e.toString());
            }
        }
        log.debug("AuditService worker stopped");
    }
}
