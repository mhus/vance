package de.mhus.vance.brain.hooks;

import de.mhus.vance.api.eventlog.EventType;
import de.mhus.vance.api.hooks.HookType;
import de.mhus.vance.shared.eventlog.EventLogService;
import de.mhus.vance.shared.inbox.InboxItemService;
import de.mhus.vance.shared.settings.SettingService;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Subscribes to {@link HookFireableEvent} and fans every fire-able
 * event out to all matching hooks in the project. Runs each hook on a
 * bounded thread-pool (single-thread-per-hook within one event tick is
 * fine — different hooks parallelise, the dispatcher does not), then
 * writes the terminal event-log row.
 *
 * <p>This is the single integration point between the domain event
 * stream and the hook subsystem. Trigger emitters publish a
 * {@link HookFireableEvent} via Spring's
 * {@link org.springframework.context.ApplicationEventPublisher} —
 * everything from there is the dispatcher's responsibility.
 */
@Component
@Slf4j
public class HookDispatcher implements DisposableBean {

    private final HookRegistry registry;
    private final JsHookRunner jsRunner;
    private final LlmHookRunner llmRunner;
    private final EventLogService eventLogService;
    private final InboxItemService inboxService;
    private final SettingService settingService;

    private final HttpClient httpClient;
    private final ExecutorService runnerPool;

    @Value("${vance.hooks.http.allowPrivateNetworks:false}")
    private boolean defaultAllowPrivateNetworks;

    @Value("${vance.hooks.http.brainPublicHosts:}")
    private String defaultBrainPublicHosts;

    public HookDispatcher(
            HookRegistry registry,
            JsHookRunner jsRunner,
            LlmHookRunner llmRunner,
            EventLogService eventLogService,
            InboxItemService inboxService,
            SettingService settingService) {
        this.registry = registry;
        this.jsRunner = jsRunner;
        this.llmRunner = llmRunner;
        this.eventLogService = eventLogService;
        this.inboxService = inboxService;
        this.settingService = settingService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
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
            // submit each hook on a separate thread so a long one
            // doesn't block its siblings within the same fire.
            runnerPool.submit(() -> runOne(def, event));
        }
    }

    private void runOne(HookDef def, HookFireableEvent event) {
        String correlationId = "hook_" + UUID.randomUUID();
        Instant firedAt = event.firedAt() == null ? Instant.now() : event.firedAt();
        HookContext ctx = new HookContext(
                event.tenantId(), event.projectId(),
                def.event(), def.name(), correlationId, firedAt);

        // TRIGGERED row first so the run is observable even if it
        // immediately throws.
        Map<String, Object> triggerPayload = new LinkedHashMap<>();
        triggerPayload.put("type", def.type().name().toLowerCase(java.util.Locale.ROOT));
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

        // Build the host-API stack scoped to this hook run.
        HookSettingsView settingsView = new HookSettingsView(
                settingService, ctx.tenantId(), ctx.projectId());
        Set<String> brainHosts = resolveBrainPublicHosts(
                ctx.tenantId(), ctx.projectId());
        boolean allowPrivate = resolveAllowPrivateNetworks(
                ctx.tenantId(), ctx.projectId());
        HookHttpClient httpApi = new HookHttpClient(
                httpClient, def.timeout(), allowPrivate, brainHosts, def.name());
        HookInboxClient inboxApi = new HookInboxClient(
                inboxService, ctx.tenantId(), def.name(),
                defaultRecipient(def), def.createdByUserId());
        HookLog logApi = new HookLog(ctx);
        HookHostApi hostApi = new HookHostApi(
                ctx, event.payload(), httpApi, inboxApi, logApi, settingsView);

        HookRunner runner = def.type() == HookType.LLM ? llmRunner : jsRunner;
        HookRunResult result;
        try {
            result = runner.run(def, ctx, event.payload(), hostApi);
        } catch (RuntimeException ex) {
            log.warn("hook '{}' raised an unexpected exception: {}",
                    def.sourceKey(), ex.toString(), ex);
            result = HookRunResult.failed(
                    Duration.ZERO, "exec", ex.getMessage() == null
                            ? ex.getClass().getSimpleName() : ex.getMessage());
        }

        EventType terminalType = switch (result.outcome()) {
            case COMPLETED -> EventType.COMPLETED;
            case FAILED -> EventType.FAILED;
            case SKIPPED -> EventType.SKIPPED;
        };
        eventLogService.append(
                event.tenantId(), event.projectId(),
                def.sourceKey(),
                terminalType, correlationId,
                /*sessionId*/ null, /*processId*/ null,
                def.createdByUserId(),
                result.toPayload());

        if (result.outcome() == HookRunResult.Outcome.FAILED) {
            log.info("hook '{}' FAILED phase={} message={}",
                    def.sourceKey(), result.errorPhase(), result.errorMessage());
        } else if (result.outcome() == HookRunResult.Outcome.COMPLETED) {
            log.debug("hook '{}' COMPLETED durationMs={} actions={}",
                    def.sourceKey(), result.duration().toMillis(),
                    result.actionCount());
        }
    }

    private String defaultRecipient(HookDef def) {
        if (def.createdByUserId() != null && !def.createdByUserId().isBlank()) {
            return def.createdByUserId();
        }
        // No createdBy on the document — fall back to a system identity
        // so the InboxItemService.create call doesn't blow up on
        // assignedToUserId blank. Operators can route these via a
        // project-level rule in v2.
        return "system:hook";
    }

    private boolean resolveAllowPrivateNetworks(String tenantId, String projectId) {
        String v = settingService.getStringValueCascade(
                tenantId, projectId, null, "hooks.http.allowPrivateNetworks");
        if (v == null) return defaultAllowPrivateNetworks;
        return "true".equalsIgnoreCase(v.trim());
    }

    private Set<String> resolveBrainPublicHosts(String tenantId, String projectId) {
        String v = settingService.getStringValueCascade(
                tenantId, projectId, null, "vance.brain.publicHosts");
        String raw = v == null ? defaultBrainPublicHosts : v;
        if (raw == null || raw.isBlank()) return Set.of();
        List<String> out = new ArrayList<>();
        for (String part : Arrays.asList(raw.split(","))) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return Set.copyOf(out);
    }

    @Override
    public void destroy() {
        runnerPool.shutdownNow();
    }
}
