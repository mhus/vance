package de.mhus.vance.brain.wakeup;

import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * In-memory registry of self-wakeup timers. A process schedules a
 * delayed delivery of {@code ProcessEvent(SCHEDULED_WAKEUP)} into its
 * own inbox; when the timer fires, the registry dispatches through
 * {@link EngineMessageRouter} so the local-direct or cross-pod path is
 * picked automatically.
 *
 * <p><b>Lifecycle.</b> Wakeups are bound to a process id. When a
 * process transitions to a terminal status, {@code
 * WakeupLifecycleListener} calls {@link #cancelAll(String)} so leaked
 * timers don't survive their owner. On pod shutdown, the
 * {@link #shutdown() @PreDestroy hook} interrupts the executor and
 * drops all in-flight scheduling.
 *
 * <p><b>Cross-pod note.</b> The registry runs on the pod that owns the
 * scheduling tool call. If the process migrates to another pod before
 * the timer fires (currently only possible for the per-user "_user_*"
 * hub project — {@code planning/eddie-engine.md} §6 / memory
 * {@code user_projects_no_home_pod.md}), the dispatch still goes
 * through the router and will be pushed cross-pod via WS. If the
 * target process is already gone, the router logs and drops it.
 *
 * <p>Plan: {@code planning/wakeup-and-exec.md} Phase 1.
 */
@Service
@Slf4j
public class WakeupRegistry {

    /**
     * Per-process inner map keyed by correlationId. Outer map is
     * keyed by processId. Two layers so cancelAll(processId) is O(1)
     * and individual cancellation can be scoped to "this process"
     * without consulting a process-id index.
     */
    private final Map<String, Map<String, Entry>> entries = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler;
    private final ObjectProvider<EngineMessageRouter> routerProvider;

    public WakeupRegistry(ObjectProvider<EngineMessageRouter> routerProvider) {
        this.routerProvider = routerProvider;
        this.scheduler = Executors.newScheduledThreadPool(4, namedDaemonThreadFactory());
    }

    /**
     * Schedules a self-wakeup for {@code processId} after {@code delay}.
     *
     * @param processId target process — wakeup will arrive on its own inbox
     * @param delay     time from now; must be positive
     * @param label     short human-readable hint (for logs / list / engine drain)
     * @param payload   caller-supplied structured data; may be {@code null}
     * @return correlationId — opaque, use with
     *         {@link #cancel(String, String)} to revoke
     */
    public String schedule(
            String processId,
            Duration delay,
            String label,
            @Nullable Map<String, Object> payload) {
        if (processId == null || processId.isBlank()) {
            throw new IllegalArgumentException("processId is required");
        }
        if (delay == null || delay.isNegative() || delay.isZero()) {
            throw new IllegalArgumentException("delay must be positive");
        }
        if (label == null) {
            throw new IllegalArgumentException("label is required");
        }
        String correlationId = UUID.randomUUID().toString();
        Instant fireAt = Instant.now().plus(delay);
        ScheduledFuture<?> future = scheduler.schedule(
                () -> fire(processId, correlationId),
                delay.toMillis(),
                TimeUnit.MILLISECONDS);
        Entry entry = new Entry(correlationId, label, fireAt, payload, future);
        entries.computeIfAbsent(processId, p -> new ConcurrentHashMap<>())
                .put(correlationId, entry);
        log.debug("scheduled wakeup process={} correlationId={} fireAt={} label='{}'",
                processId, correlationId, fireAt, label);
        return correlationId;
    }

    /**
     * Cancels a previously-scheduled wakeup. Returns {@code true} when
     * the wakeup existed and was removed before firing; {@code false}
     * if the correlationId is unknown for this process or the timer
     * already fired.
     *
     * <p>Scoped to {@code processId} so a process can't cancel
     * another process's wakeups by guessing correlationIds.
     */
    public boolean cancel(String processId, String correlationId) {
        Map<String, Entry> processEntries = entries.get(processId);
        if (processEntries == null) {
            return false;
        }
        Entry entry = processEntries.remove(correlationId);
        if (entry == null) {
            return false;
        }
        boolean cancelled = entry.future.cancel(false);
        log.debug("cancelled wakeup process={} correlationId={} cancelled={}",
                processId, correlationId, cancelled);
        return cancelled;
    }

    /**
     * Cancels every outstanding wakeup for {@code processId}. Called
     * from the lifecycle listener when the owning process terminates
     * so leftover timers don't fire into a closed inbox.
     */
    public void cancelAll(String processId) {
        Map<String, Entry> processEntries = entries.remove(processId);
        if (processEntries == null) {
            return;
        }
        int count = 0;
        for (Entry entry : processEntries.values()) {
            if (entry.future.cancel(false)) {
                count++;
            }
        }
        if (count > 0) {
            log.debug("cancelled {} wakeups for terminated process={}", count, processId);
        }
    }

    /**
     * Read-only snapshot of all active wakeups for a process. The
     * returned list is a copy — safe to iterate while other threads
     * schedule or cancel.
     */
    public List<WakeupHandle> list(String processId) {
        Map<String, Entry> processEntries = entries.get(processId);
        if (processEntries == null) {
            return Collections.emptyList();
        }
        List<WakeupHandle> out = new ArrayList<>(processEntries.size());
        for (Entry entry : processEntries.values()) {
            out.add(new WakeupHandle(entry.correlationId, entry.label, entry.fireAt));
        }
        return out;
    }

    /**
     * Drops the executor on graceful pod shutdown. In-flight timers
     * are interrupted; data loss is documented in
     * {@code planning/wakeup-and-exec.md} §6/§7.
     */
    @PreDestroy
    public void shutdown() {
        log.info("WakeupRegistry shutdown — dropping {} processes worth of timers",
                entries.size());
        entries.clear();
        scheduler.shutdownNow();
    }

    /**
     * Executor-thread entry point. Removes its own entry from the
     * map (idempotent if cancellation raced and already removed it),
     * then dispatches the SCHEDULED_WAKEUP event through the engine
     * message router.
     *
     * <p>If the entry is already gone, this is a no-op: cancellation
     * won the race — either explicit {@code cancel} or {@code
     * cancelAll}.
     */
    private void fire(String processId, String correlationId) {
        Map<String, Entry> processEntries = entries.get(processId);
        if (processEntries == null) {
            log.debug("wakeup fired for unknown process={} correlationId={}",
                    processId, correlationId);
            return;
        }
        Entry entry = processEntries.remove(correlationId);
        if (entry == null) {
            log.debug("wakeup fire raced with cancellation process={} correlationId={}",
                    processId, correlationId);
            return;
        }
        // Drop the inner map when it goes empty so the outer map
        // doesn't accumulate empty shells for long-lived processes.
        if (processEntries.isEmpty()) {
            entries.remove(processId, processEntries);
        }
        try {
            PendingMessageDocument doc = PendingMessageDocument.builder()
                    .type(PendingMessageType.PROCESS_EVENT)
                    .at(Instant.now())
                    .sourceProcessId(processId)
                    .eventType(ProcessEventType.SCHEDULED_WAKEUP)
                    .content(entry.label)
                    .payload(buildPayload(correlationId, entry.label, entry.payload))
                    .build();
            boolean ok = routerProvider.getObject().dispatch(processId, processId, doc);
            if (!ok) {
                log.warn("wakeup dispatch dropped process={} correlationId={}",
                        processId, correlationId);
            } else {
                log.debug("wakeup fired process={} correlationId={} label='{}'",
                        processId, correlationId, entry.label);
            }
        } catch (RuntimeException e) {
            log.warn("wakeup dispatch failed process={} correlationId={}: {}",
                    processId, correlationId, e.toString(), e);
        }
    }

    /**
     * Wraps caller payload with envelope metadata. The engine drain
     * sees correlationId + label at the top level so it can hook
     * SCHEDULED_WAKEUP events without digging into nested user data.
     */
    private static Map<String, Object> buildPayload(
            String correlationId, String label, @Nullable Map<String, Object> userPayload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("correlationId", correlationId);
        envelope.put("label", label);
        if (userPayload != null && !userPayload.isEmpty()) {
            envelope.put("payload", userPayload);
        }
        return envelope;
    }

    private static ThreadFactory namedDaemonThreadFactory() {
        AtomicLong counter = new AtomicLong();
        return r -> {
            Thread t = new Thread(r, "vance-wakeup-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    private record Entry(
            String correlationId,
            String label,
            Instant fireAt,
            @Nullable Map<String, Object> payload,
            ScheduledFuture<?> future) {
    }
}
