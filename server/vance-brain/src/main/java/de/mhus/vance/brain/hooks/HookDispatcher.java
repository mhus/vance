package de.mhus.vance.brain.hooks;

import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.api.eventlog.EventType;
import de.mhus.vance.brain.action.ActionExecutorRegistry;
import de.mhus.vance.brain.action.ActionOutcome;
import de.mhus.vance.brain.action.ActionResult;
import de.mhus.vance.brain.action.TriggerContext;
import de.mhus.vance.brain.action.TriggerKind;
import de.mhus.vance.shared.eventlog.EventLogService;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Subscribes to {@link HookFireableEvent} and fans every fire-able
 * event out to all matching hooks in the project. Each hook's
 * {@link HookDef#action()} is dispatched through the central
 * {@link ActionExecutorRegistry} — same pipeline as scheduler ticks
 * and HTTP events. Hooks gain the full {@code TriggerAction}
 * vocabulary (Recipe / Script / Workflow); the dispatcher's job
 * shrinks to "find matching hooks, write event-log rows, run on a
 * bounded thread-pool".
 *
 * <p>The Brain-internal lifecycle payload (event-shape per
 * {@code HookEventName}) is merged into the action params under the
 * {@code event} key — scripts read it as {@code vance.params.event},
 * recipes / workflows see it under {@code params.event}.
 */
@Component
@Slf4j
public class HookDispatcher implements DisposableBean {

    private final HookRegistry registry;
    private final ActionExecutorRegistry actionRegistry;
    private final EventLogService eventLogService;
    private final ExecutorService runnerPool;

    public HookDispatcher(
            HookRegistry registry,
            ActionExecutorRegistry actionRegistry,
            EventLogService eventLogService) {
        this.registry = registry;
        this.actionRegistry = actionRegistry;
        this.eventLogService = eventLogService;
        AtomicLong tid = new AtomicLong();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "vance-hook-runner-" + tid.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.runnerPool = Executors.newFixedThreadPool(4, tf);
    }

    @EventListener
    @Async
    public void onHookFireable(HookFireableEvent event) {
        dispatch(event);
    }

    /** Entry point — exposed for direct invocation in tests. */
    public void dispatch(HookFireableEvent event) {
        List<HookDef> defs = registry.hooksFor(
                event.tenantId(), event.projectId(), event.event());
        if (defs.isEmpty()) return;

        for (HookDef def : defs) {
            if (!def.enabled()) continue;
            runnerPool.submit(() -> runOne(def, event));
        }
    }

    private void runOne(HookDef def, HookFireableEvent event) {
        String correlationId = "hook_" + UUID.randomUUID();
        Instant firedAt = event.firedAt() == null ? Instant.now() : event.firedAt();

        Map<String, Object> triggerPayload = new LinkedHashMap<>();
        triggerPayload.put("actionType", def.actionType());
        if (def.description() != null) {
            triggerPayload.put("description", def.description());
        }
        eventLogService.append(
                event.tenantId(), event.projectId(),
                def.sourceKey(),
                EventType.TRIGGERED, correlationId,
                /*sessionId*/ null, /*processId*/ null,
                def.createdByUserId(),
                triggerPayload);

        // Merge the lifecycle-event payload into the action's params
        // under "event". Subscribers read it via vance.params.event in
        // scripts, or as params.event in recipes/workflows.
        TriggerAction action = withEventInParams(def.action(), event.payload());
        TriggerContext ctx = new TriggerContext(
                event.tenantId(), event.projectId(),
                /*resolvedRunAs*/ def.action().runAs() != null
                        ? def.action().runAs() : def.createdByUserId(),
                correlationId, def.sourceKey(),
                /*parentSessionId*/ null, /*parentProcessId*/ null);

        Instant start = Instant.now();
        ActionResult result;
        try {
            result = actionRegistry.execute(action, ctx, TriggerKind.HOOK);
        } catch (RuntimeException ex) {
            log.warn("hook '{}' raised an unexpected exception during dispatch: {}",
                    def.sourceKey(), ex.toString(), ex);
            result = ActionResult.failure(ActionOutcome.TECHNICAL_ERROR,
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
                    null);
        }
        Duration duration = Duration.between(start, Instant.now());

        EventType terminalType;
        Map<String, Object> terminalPayload = new LinkedHashMap<>();
        terminalPayload.put("durationMs", duration.toMillis());
        ActionOutcome outcome = result.outcome();
        if (outcome == ActionOutcome.SCHEDULED || outcome == ActionOutcome.SUCCESS) {
            terminalType = EventType.COMPLETED;
            if (result.spawnedId() != null) {
                terminalPayload.put("spawnedId", result.spawnedId());
            }
            if (result.output() != null) {
                terminalPayload.put("output", result.output());
            }
        } else {
            terminalType = EventType.FAILED;
            terminalPayload.put("outcome", outcome.name());
            if (result.errorMessage() != null) {
                terminalPayload.put("error", result.errorMessage());
            }
        }
        eventLogService.append(
                event.tenantId(), event.projectId(),
                def.sourceKey(),
                terminalType, correlationId,
                /*sessionId*/ null, /*processId*/ null,
                def.createdByUserId(),
                terminalPayload);

        if (terminalType == EventType.FAILED) {
            log.info("hook '{}' FAILED outcome={} error={}",
                    def.sourceKey(), outcome, result.errorMessage());
        } else {
            log.debug("hook '{}' {} durationMs={}{}",
                    def.sourceKey(), terminalType,
                    duration.toMillis(),
                    result.spawnedId() == null ? "" : " spawnedId=" + result.spawnedId());
        }
    }

    /**
     * Returns a copy of {@code action} with the lifecycle-event payload
     * merged into its params under the {@code event} key. Sealed
     * pattern-match — exhaustive over the three TriggerAction variants.
     */
    @SuppressWarnings("unchecked")
    private static TriggerAction withEventInParams(
            TriggerAction action,
            @Nullable Map<String, @Nullable Object> eventPayload) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (action.params() != null) merged.putAll(action.params());
        if (eventPayload != null && !eventPayload.isEmpty()) {
            merged.put("event", new LinkedHashMap<>(eventPayload));
        }
        return switch (action) {
            case TriggerAction.Recipe r -> new TriggerAction.Recipe(
                    r.recipe(), r.engineOverride(), r.processName(),
                    r.title(), r.goal(), r.inheritContextLevel(),
                    r.connectionProfile(), r.initialMessage(),
                    merged, r.runAs());
            case TriggerAction.Script s -> new TriggerAction.Script(
                    s.source(), s.dirName(), s.path(),
                    s.timeoutSeconds(), merged, s.runAs());
            case TriggerAction.Workflow w -> new TriggerAction.Workflow(
                    w.workflow(), merged, w.runAs());
        };
    }

    @Override
    public void destroy() {
        runnerPool.shutdownNow();
    }
}
