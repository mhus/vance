package de.mhus.vance.brain.ursaeventtrigger;

import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.brain.action.ActionExecutorRegistry;
import de.mhus.vance.brain.action.ActionOutcome;
import de.mhus.vance.brain.action.ActionResult;
import de.mhus.vance.brain.action.TriggerContext;
import de.mhus.vance.brain.action.TriggerKind;
import de.mhus.vance.brain.magrathea.MagratheaWorkflowService;
import de.mhus.vance.brain.ursascheduler.SystemSessionResolver;
import de.mhus.vance.shared.ursaevents.UrsaEventLoader;
import de.mhus.vance.shared.ursaevents.ResolvedUrsaEvent;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.settings.SettingService;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Runtime side of the events subsystem: resolves the event in the
 * cascade, checks the HTTP method, performs bearer-token authentication
 * (literal or via setting cascade), and delegates the workflow spawn
 * to {@link MagratheaWorkflowService}.
 *
 * <p>Lives in {@code vance-brain} because (a) it depends on
 * {@code MagratheaWorkflowService} which is brain-only and (b) the brain
 * is the only deployment surface that exposes the {@code /brain/...}
 * REST endpoints. {@code vance-anus} (pod runtime) does not see this
 * class, matching the user instruction not to wire workflow spawn
 * outside the brain.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UrsaEventService {

    /** Reserved param key under which the request payload is exposed to the workflow. */
    public static final String PAYLOAD_PARAM_KEY = "payload";

    /** Micrometer counter for trigger calls. Tags: {@code event}, {@code outcome}. */
    private static final String METRIC_TRIGGERS = "vance.ursaevents.triggers";

    /** Micrometer timer for successful trigger latency. Tag: {@code event}. */
    private static final String METRIC_TRIGGER_DURATION = "vance.ursaevents.trigger.duration";

    private final UrsaEventLoader eventLoader;
    private final SettingService settingService;
    private final MetricService metricService;
    /** Optional — Magrathea is feature-flagged; when off, workflow-events return 503. */
    private final ObjectProvider<MagratheaWorkflowService> workflowServiceProvider;
    private final ActionExecutorRegistry actionExecutorRegistry;
    private final SystemSessionResolver systemSessionResolver;
    /** LLM-facing materialised per-trigger log — see {@link UrsaEventLogService}. */
    private final UrsaEventLogService eventLogService;
    /**
     * Lazy — the locator pulls in the lifecycle and cluster services, and an
     * event trigger must not drag that whole graph into this service's
     * construction.
     */
    private final ObjectProvider<de.mhus.vance.brain.project.ProjectLocator> projectLocatorProvider;
    private final ObjectProvider<de.mhus.vance.brain.project.ProjectManagerService>
            projectManagerProvider;
    private final UrsaEventForwarder forwarder;

    /**
     * Runs {@code async: true} script events off the request thread.
     *
     * <p>Virtual threads: these tasks are dominated by whatever the script
     * waits on (an LLM call, a document write), so a pool size would cap
     * concurrency without saving anything. The script's own
     * {@code timeoutSeconds} bounds each task.
     */
    private final ExecutorService asyncScriptExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("event-async-", 0).factory());

    /**
     * Cap on the output rendered into the per-trigger log document.
     * A script may return arbitrarily much; the log is a diagnostic, not
     * a data sink.
     */
    private static final int LOG_OUTPUT_MAX_CHARS = 2000;

    /**
     * Outcome of a successful event trigger. For backwards-compat the
     * field names carry the workflow nomenclature but apply to any
     * trigger variant: {@code workflowName} is the workflow/recipe name
     * or a {@code script:<path>} sentinel; {@code workflowRunId} is the
     * workflowRunId, processId, or {@code null} for script-runs.
     *
     * <p>{@code correlationId} is the {@code evt_<uuid>} identifier
     * minted by the trigger entry-point and used as the suffix of the
     * matching {@link UrsaEventLogService} document. Callers (tools,
     * controller) can echo it back so the model / UI can read the
     * per-trigger log without listing the folder.
     *
     * <p>{@code output} carries what the action produced when it ran to
     * completion — today that is a script's return value, mapped by
     * {@code ScriptOutcomeMapper}. It is {@code null} for spawns and for
     * {@code async: true} runs, where by definition nothing has been
     * produced yet. Exactly one of {@code workflowRunId} and
     * {@code output} is meaningful per trigger, mirroring
     * {@code ActionResult.scheduled} vs {@code ActionResult.success}.
     */
    public record UrsaEventTriggerResult(
            String workflowName,
            @Nullable String workflowRunId,
            @Nullable Map<String, Object> output,
            String correlationId,
            Instant firedAt) {}

    /**
     * Trigger flow:
     * <ol>
     *   <li>resolve event via cascade ({@code project → _vance}) → 404</li>
     *   <li>{@code enabled: false} or method-not-allowed → 404 (don't leak existence)</li>
     *   <li>bearer auth check (when {@code auth:} block configured) → 401</li>
     *   <li>{@link MagratheaWorkflowService#start} (start fails → 502 / 400)</li>
     * </ol>
     *
     * <p>{@code payload} is nested under {@link #PAYLOAD_PARAM_KEY} in
     * the params handed to the workflow — see {@code specification/events.md} §4.
     */
    public UrsaEventTriggerResult trigger(
            String tenantId,
            String projectId,
            String eventName,
            String httpMethod,
            @Nullable String bearerToken,
            @Nullable Object payload) {
        return trigger(tenantId, projectId, eventName, httpMethod, bearerToken, payload,
                /*alreadyForwarded*/ false);
    }

    /**
     * @param alreadyForwarded set when another pod routed this request here.
     *        Such a request is executed locally without resolving the owner
     *        again — one hop, so a lease changing hands mid-flight cannot make
     *        two pods bounce it back and forth.
     */
    public UrsaEventTriggerResult trigger(
            String tenantId,
            String projectId,
            String eventName,
            String httpMethod,
            @Nullable String bearerToken,
            @Nullable Object payload,
            boolean alreadyForwarded) {
        long startNanos = System.nanoTime();
        Instant firedAt = Instant.now();
        String correlationId = UrsaEventLogService.TriggerOutcome.mintCorrelationId();
        // Tracking state for the per-trigger log document — set in
        // each branch, written to a document in the finally block
        // unless the event is unknown (skipLog) to avoid arbitrary-
        // webhook-spam pollution of the document layer.
        String outcome = "incomplete";
        String runAs = null;
        String targetName = null;
        String spawnedId = null;
        String outputSummary = null;
        String errorMessage = null;
        boolean skipLog = false;

        try {
            ResolvedUrsaEvent event;
            try {
                event = eventLoader.load(tenantId, projectId, eventName)
                        .orElse(null);
            } catch (RuntimeException ex) {
                countOutcome(eventName, "bad_payload");
                outcome = "bad_payload";
                errorMessage = ex.getMessage();
                throw ex;
            }
            if (event == null) {
                countOutcome(eventName, "not_found");
                outcome = "not_found";
                skipLog = true;
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Event '" + eventName + "' not found");
            }
            runAs = event.effectiveRunAs();

            if (!event.enabled()) {
                // Treat disabled events as 404 — don't leak that the event
                // exists. Caller can flip `enabled: true` in the YAML to re-enable.
                log.debug("Event '{}/{}/{}' disabled — returning 404",
                        tenantId, projectId, eventName);
                countOutcome(eventName, "disabled");
                outcome = "disabled";
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Event '" + eventName + "' not found");
            }

            if (!event.acceptsMethod(httpMethod)) {
                countOutcome(eventName, "method_not_allowed");
                outcome = "method_not_allowed";
                errorMessage = "method " + httpMethod + " not in " + event.methods();
                throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED,
                        "Event '" + eventName + "' does not accept " + httpMethod);
            }

            if (event.requiresAuth()) {
                String expected = resolveExpectedToken(tenantId, projectId, event);
                if (expected == null || expected.isBlank()) {
                    log.warn("Event '{}/{}/{}' requires auth but token is unresolved "
                                    + "(setting '{}' empty)",
                            tenantId, projectId, eventName, event.tokenSettingKey());
                    countOutcome(eventName, "auth_misconfigured");
                    outcome = "auth_misconfigured";
                    errorMessage = "tokenSettingKey '" + event.tokenSettingKey() + "' unresolved";
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "Event auth is misconfigured");
                }
                if (!constantTimeEquals(expected, bearerToken)) {
                    countOutcome(eventName, "unauthorized");
                    outcome = "unauthorized";
                    errorMessage = "bearer token mismatch";
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                            "Invalid or missing bearer token");
                }
            }

            if (!alreadyForwarded) {
                String owner = bringUpAndFindOwner(tenantId, projectId, eventName);
                if (owner != null) {
                    de.mhus.vance.api.ursaevents.EventTriggerResponse remote = forwarder.forward(
                            owner, tenantId, projectId, eventName,
                            httpMethod, bearerToken, payload);
                    countOutcome(eventName, "forwarded");
                    outcome = "forwarded";
                    targetName = remote.getWorkflowName();
                    spawnedId = remote.getWorkflowRunId();
                    // correlationId and firedAt stay ours: they identify *this*
                    // request, and the owning pod wrote its own log entry under
                    // its own id.
                    return new UrsaEventTriggerResult(
                            remote.getWorkflowName(), remote.getWorkflowRunId(),
                            remote.getOutput(), correlationId, firedAt);
                }
            }

            LogIdentity logIdentity = new LogIdentity(
                    UrsaEventLogService.TriggerSource.PUBLIC, httpMethod, /*triggeredBy*/ null);
            // An async script dispatch writes its own log when it finishes,
            // with this same correlationId — writing here too would race it.
            skipLog = asyncDispatch(event);

            UrsaEventTriggerResult result;
            try {
                result = executeAction(tenantId, projectId, eventName, event, payload,
                        correlationId, firedAt, logIdentity);
            } catch (ResponseStatusException ex) {
                // executeAction already tagged the metric outcome; copy
                // it into our log tracking so the document mirrors the
                // metric vocab. The failure happened before dispatch, so
                // this side owns the log again.
                skipLog = false;
                outcome = mapResponseStatusToOutcome(ex);
                errorMessage = ex.getReason();
                throw ex;
            }
            countOutcome(eventName, "success");
            metricService.timer(METRIC_TRIGGER_DURATION, "event", eventName)
                    .record(Duration.ofNanos(System.nanoTime() - startNanos));
            log.info("Event '{}/{}/{}' fired target='{}' spawnedId='{}'",
                    tenantId, projectId, eventName, result.workflowName(), result.workflowRunId());
            outcome = "success";
            targetName = result.workflowName();
            spawnedId = result.workflowRunId();
            outputSummary = summariseOutput(result.output(), event.outputVisibleToAgents());
            return result;
        } finally {
            if (!skipLog) {
                long durationMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
                eventLogService.record(correlationId,
                        new UrsaEventLogService.TriggerOutcome(
                                tenantId, projectId, eventName,
                                UrsaEventLogService.TriggerSource.PUBLIC,
                                httpMethod, /*triggeredBy*/ null,
                                firedAt, durationMs, outcome,
                                targetName, spawnedId, runAs,
                                /*payloadContentType*/ null,
                                /*payloadSizeBytes*/ -1,
                                outputSummary, errorMessage));
            }
        }
    }

    /**
     * Build the {@link TriggerAction}, route it via
     * {@link ActionExecutorRegistry}, and translate the result to a
     * {@link UrsaEventTriggerResult} or an {@link HttpStatus} error. Used by
     * both {@link #trigger} and {@link #triggerAdmin}.
     */
    private UrsaEventTriggerResult executeAction(
            String tenantId, String projectId, String eventName,
            ResolvedUrsaEvent event, @Nullable Object payload,
            String correlationId, Instant firedAt, LogIdentity logIdentity) {

        Map<String, Object> mergedParams = new LinkedHashMap<>(event.params());
        if (payload != null) {
            mergedParams.put(PAYLOAD_PARAM_KEY, payload);
        }

        TriggerAction action;
        try {
            action = event.toTriggerAction(mergedParams);
        } catch (RuntimeException ex) {
            log.warn("Event '{}/{}/{}' action build failed: {}",
                    tenantId, projectId, eventName, ex.toString());
            countOutcome(eventName, "bad_payload");
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Event action build failed: " + ex.getMessage(), ex);
        }

        // Workflow-trigger requires Magrathea — keep the 503 semantics.
        if (action instanceof TriggerAction.Workflow
                && workflowServiceProvider.getIfAvailable() == null) {
            log.warn("Event '{}/{}/{}' wants workflow '{}' but Magrathea is not active "
                            + "(vance.services.magrathea=false)",
                    tenantId, projectId, eventName, event.workflow());
            countOutcome(eventName, "magrathea_unavailable");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Magrathea workflow subsystem is not active");
        }

        // Recipe-trigger needs a system session — same pattern as the
        // scheduler. The session resolver tags it with the event name.
        TriggerContext context;
        if (action instanceof TriggerAction.Recipe) {
            String runAs = event.effectiveRunAs();
            if (runAs == null) {
                countOutcome(eventName, "bad_payload");
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Event '" + eventName + "' has no runAs (set 'runAs:' or document createdBy)");
            }
            SessionDocument session = systemSessionResolver.resolve(
                    tenantId, projectId, "event_" + eventName, runAs);
            context = TriggerContext.sessioned(
                    tenantId, projectId,
                    event.effectiveRunAs(),
                    correlationId,
                    "event:" + eventName,
                    session.getSessionId(),
                    /*parentProcessId*/ null);
        } else {
            context = TriggerContext.standalone(
                    tenantId, projectId,
                    event.effectiveRunAs(),
                    correlationId,
                    "event:" + eventName,
                    /*parentProcessId*/ null);
        }

        if (asyncDispatch(event)) {
            dispatchAsync(tenantId, projectId, eventName, action, context,
                    correlationId, firedAt, logIdentity, event.effectiveRunAs(),
                    event.outputVisibleToAgents());
            return new UrsaEventTriggerResult(
                    targetNameOf(action, eventName), /*spawnedId*/ null,
                    /*output*/ null, correlationId, firedAt);
        }

        ActionResult result;
        try {
            result = actionExecutorRegistry.execute(action, context, TriggerKind.EVENT);
        } catch (RuntimeException ex) {
            log.warn("Event '{}/{}/{}' executor dispatch failed: {}",
                    tenantId, projectId, eventName, ex.toString());
            countOutcome(eventName, "spawn_failed");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Event dispatch failed: " + ex.getMessage(), ex);
        }
        if (result.outcome().isFailure()) {
            HttpStatus mapped = result.outcome() == ActionOutcome.PERMISSION_ERROR
                    ? HttpStatus.FORBIDDEN
                    : HttpStatus.BAD_GATEWAY;
            countOutcome(eventName, "spawn_failed");
            throw new ResponseStatusException(mapped,
                    "Event execution failed: "
                            + (result.errorMessage() == null ? result.outcome().name() : result.errorMessage()));
        }

        String targetName = targetNameOf(action, eventName);
        return new UrsaEventTriggerResult(
                targetName, result.spawnedId(), result.output(), correlationId, firedAt);
    }

    private static String targetNameOf(TriggerAction action, String eventName) {
        if (action instanceof TriggerAction.Recipe r) return r.recipe();
        if (action instanceof TriggerAction.Workflow w) return w.workflow();
        if (action instanceof TriggerAction.Script s) return "script:" + s.path();
        return eventName;
    }

    /**
     * {@code true} when this event is dispatched off-thread and therefore
     * owns its own log write.
     *
     * <p>Only scripts qualify: a recipe or workflow spawn already returns
     * immediately and its log is written by the caller's {@code finally}
     * as before. Nothing about those changes.
     */
    static boolean asyncDispatch(ResolvedUrsaEvent event) {
        return event.script() != null && event.resolvedAsync();
    }

    /**
     * Runs a script event off-thread and writes the log when it finishes.
     *
     * <p>The caller returns immediately without an output and skips its own
     * log write, so the single log document is written once, by this task,
     * with the same correlationId — the {@code logPath} the caller was
     * handed stays correct, it just materialises a moment later.
     *
     * <p>Safe to move off the request thread because everything the
     * executor needs travels in its arguments: identity is in the
     * {@link TriggerContext}, not in a thread-bound holder.
     */
    private void dispatchAsync(String tenantId, String projectId, String eventName,
            TriggerAction action, TriggerContext context, String correlationId,
            Instant firedAt, LogIdentity logIdentity, @Nullable String runAs,
            boolean outputVisible) {

        long startNanos = System.nanoTime();
        asyncScriptExecutor.submit(() -> {
            String outcome;
            String errorMessage = null;
            Map<String, Object> output = null;
            try {
                ActionResult result =
                        actionExecutorRegistry.execute(action, context, TriggerKind.EVENT);
                if (result.outcome().isFailure()) {
                    outcome = "spawn_failed";
                    errorMessage = result.errorMessage() == null
                            ? result.outcome().name() : result.errorMessage();
                } else {
                    outcome = "success";
                    output = result.output();
                }
            } catch (RuntimeException ex) {
                outcome = "spawn_failed";
                errorMessage = ex.toString();
                log.warn("Async event '{}/{}/{}' failed: {}",
                        tenantId, projectId, eventName, ex.toString());
            }
            // Deliberately no metric here: the dispatch was already counted
            // as a success by the caller, and counting again would make the
            // per-event totals mean two different things at once.
            long durationMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            eventLogService.record(correlationId,
                    new UrsaEventLogService.TriggerOutcome(
                            tenantId, projectId, eventName,
                            logIdentity.source(), logIdentity.httpMethod(),
                            logIdentity.triggeredBy(),
                            firedAt, durationMs, outcome,
                            targetNameOf(action, eventName), /*spawnedId*/ null, runAs,
                            /*payloadContentType*/ null, /*payloadSizeBytes*/ -1,
                            summariseOutput(output, outputVisible), errorMessage));
        });
    }

    /** Which trigger surface a log entry belongs to. */
    private record LogIdentity(
            UrsaEventLogService.TriggerSource source,
            @Nullable String httpMethod,
            @Nullable String triggeredBy) {}

    /**
     * Admin/UI variant of {@link #trigger}: skips the bearer-token and
     * HTTP-method check, but still enforces existence + {@code enabled:}.
     *
     * <p>Intended for the JWT-authenticated REST surface used by the
     * insights editor — the caller already proved tenant/project
     * privilege via the {@code BrainAccessFilter}, so demanding the
     * event's bearer token would just force operators to copy secrets
     * into the UI. {@code methods:} is intentionally ignored here: an
     * event with {@code methods: [POST]} can still be "test-fired" from
     * the admin UI without manual reconfiguration.
     */
    public UrsaEventTriggerResult triggerAdmin(
            String tenantId,
            String projectId,
            String eventName,
            @Nullable Object payload,
            @Nullable String triggeredBy) {
        long startNanos = System.nanoTime();
        Instant firedAt = Instant.now();
        String correlationId = UrsaEventLogService.TriggerOutcome.mintCorrelationId();
        // Same tracking pattern as the public trigger() above — set in
        // every branch, written once in the finally.
        String outcome = "incomplete";
        String runAs = null;
        String targetName = null;
        String spawnedId = null;
        String outputSummary = null;
        String errorMessage = null;
        boolean skipLog = false;

        try {
            ResolvedUrsaEvent event = eventLoader.load(tenantId, projectId, eventName)
                    .orElse(null);
            if (event == null) {
                countOutcomeAdmin(eventName, "not_found");
                outcome = "not_found";
                skipLog = true;
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Event '" + eventName + "' not found");
            }
            runAs = event.effectiveRunAs();

            if (!event.enabled()) {
                countOutcomeAdmin(eventName, "disabled");
                outcome = "disabled";
                errorMessage = "event is disabled";
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Event '" + eventName + "' is disabled — flip enabled: true to trigger");
            }

            LogIdentity logIdentity = new LogIdentity(
                    UrsaEventLogService.TriggerSource.ADMIN, /*httpMethod*/ null, triggeredBy);
            skipLog = asyncDispatch(event);

            UrsaEventTriggerResult result;
            try {
                result = executeAction(tenantId, projectId, eventName, event, payload,
                        correlationId, firedAt, logIdentity);
            } catch (ResponseStatusException ex) {
                // Re-tag the metric outcome under the admin source.
                skipLog = false;
                countOutcomeAdmin(eventName, mapResponseStatusToOutcome(ex));
                outcome = mapResponseStatusToOutcome(ex);
                errorMessage = ex.getReason();
                throw ex;
            }
            countOutcomeAdmin(eventName, "success");
            metricService.timer(METRIC_TRIGGER_DURATION, "event", eventName)
                    .record(Duration.ofNanos(System.nanoTime() - startNanos));
            log.info("Admin event '{}/{}/{}' fired target='{}' spawnedId='{}' triggeredBy='{}'",
                    tenantId, projectId, eventName, result.workflowName(), result.workflowRunId(),
                    triggeredBy);
            outcome = "success";
            targetName = result.workflowName();
            spawnedId = result.workflowRunId();
            outputSummary = summariseOutput(result.output(), event.outputVisibleToAgents());
            if (!event.outputVisibleToAgents()) {
                // This is the surface that skips the bearer check — the
                // caller never proved anything. Withholding happens here
                // rather than in the tool so the admin REST test-fire is
                // covered by the same rule.
                return new UrsaEventTriggerResult(result.workflowName(), result.workflowRunId(),
                        /*output*/ null, result.correlationId(), result.firedAt());
            }
            return result;
        } finally {
            if (!skipLog) {
                long durationMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
                eventLogService.record(correlationId,
                        new UrsaEventLogService.TriggerOutcome(
                                tenantId, projectId, eventName,
                                UrsaEventLogService.TriggerSource.ADMIN,
                                /*httpMethod*/ null, triggeredBy,
                                firedAt, durationMs, outcome,
                                targetName, spawnedId, runAs,
                                /*payloadContentType*/ null,
                                /*payloadSizeBytes*/ -1,
                                outputSummary, errorMessage));
            }
        }
    }

    /**
     * Renders an action's output for the log document, truncated.
     *
     * <p>Truncation is marked rather than silent — a log entry that looks
     * complete but is not is worse than one that says where it stopped.
     */
    static @Nullable String summariseOutput(@Nullable Map<String, Object> output, boolean visible) {
        if (output == null || output.isEmpty()) {
            return null;
        }
        if (!visible) {
            // The log document lives in the project's document layer, so
            // its audience is every agent that can read documents there —
            // not the caller who triggered it. Writing the output here
            // while withholding it from event_fire would make that control
            // decorative: the same agent would just read the log instead.
            // Record that something was produced, not what.
            return "[withheld — outputToAgents: false]";
        }
        String rendered = String.valueOf(output);
        if (rendered.length() <= LOG_OUTPUT_MAX_CHARS) {
            return rendered;
        }
        return rendered.substring(0, LOG_OUTPUT_MAX_CHARS)
                + "… [truncated, " + rendered.length() + " chars total]";
    }

    @PreDestroy
    void shutdown() {
        asyncScriptExecutor.shutdown();
        try {
            if (!asyncScriptExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                asyncScriptExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            asyncScriptExecutor.shutdownNow();
        }
    }

    private static String mapResponseStatusToOutcome(ResponseStatusException ex) {
        int code = ex.getStatusCode().value();
        if (code == 503) return "magrathea_unavailable";
        if (code == 502) return "spawn_failed";
        if (code == 403) return "permission_denied";
        if (code == 500) return "bad_payload";
        return "failed";
    }

    /**
     * Increments {@link #METRIC_TRIGGERS} with the public-trigger
     * source tag — distinguishes external webhook calls from admin
     * triggers in the same counter family.
     */
    private void countOutcome(String eventName, String outcome) {
        metricService.counter(METRIC_TRIGGERS,
                "event", eventName,
                "source", "public",
                "outcome", outcome).increment();
    }

    /** Admin-trigger variant of {@link #countOutcome}. */
    private void countOutcomeAdmin(String eventName, String outcome) {
        metricService.counter(METRIC_TRIGGERS,
                "event", eventName,
                "source", "admin",
                "outcome", outcome).increment();
    }

    private @Nullable String resolveExpectedToken(
            String tenantId, String projectId, ResolvedUrsaEvent event) {
        if (event.tokenLiteral() != null) return event.tokenLiteral();
        if (event.tokenSettingKey() != null) {
            // Setting cascade: project → _vance. No think-process scope
            // here — events fire ahead of any process and aren't
            // process-scoped.
            return settingService.getStringValueCascade(
                    tenantId, projectId, /*thinkProcessId*/ null, event.tokenSettingKey());
        }
        return null;
    }

    /**
     * Length-independent constant-time comparison. Protects against
     * timing attacks on the bearer-token check.
     */
    /**
     * Brings the project online before running the event's action.
     *
     * <p>Event triggers are <b>reactive</b>, so their project is deliberately
     * not kept on a pod for them ({@code ProjectOwnerRequirementService}) — a
     * webhook that fires twice a year would otherwise cost a pod slot all year.
     * The call itself is what pays for it instead: the first trigger after a
     * restart takes a cold start, and every following one finds the project
     * already up.
     *
     * <p>Deliberately placed after the event was found, enabled and
     * authenticated: an unknown name or a bad token must not be able to start
     * projects, or the endpoint becomes a way to make a stranger's cluster do
     * work.
     *
     * <p>Fail-open. If the bring does not succeed we still run — that is what
     * happened before this existed, and a half-available project is a better
     * answer to a webhook than a 500.
     */
    private @Nullable String bringUpAndFindOwner(
            String tenantId, String projectId, String eventName) {
        if (ProjectService.isPodless(projectId)) return null;
        de.mhus.vance.brain.project.ProjectLocator locator =
                projectLocatorProvider.getIfAvailable();
        if (locator == null) return null;
        try {
            // Blocking by contract: locate(autoStart) returns once the project
            // has been brought online — workspace restored, engines started,
            // status RUNNING. That wait is the point. Handing the event to a
            // pod that is still recovering would spawn onto a lane that is not
            // there yet.
            de.mhus.vance.brain.project.ProjectLocator.Location location =
                    locator.locate(tenantId, projectId, /*autoStart*/ true);
            String endpoint = location.endpoint().orElse(null);
            if (endpoint == null) return null;
            de.mhus.vance.brain.project.ProjectManagerService manager =
                    projectManagerProvider.getIfAvailable();
            if (manager != null && manager.isLocalPod(endpoint)) return null;
            return endpoint;
        } catch (RuntimeException e) {
            // Fail-open to local execution: this is what happened before events
            // were routed at all, and answering a webhook badly beats not
            // answering it.
            log.warn("Event '{}/{}/{}': could not place the project, running locally: {}",
                    tenantId, projectId, eventName, e.toString());
            return null;
        }
    }

    private static boolean constantTimeEquals(String expected, @Nullable String actual) {
        if (actual == null) return false;
        byte[] e = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] a = actual.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(e, a);
    }
}
