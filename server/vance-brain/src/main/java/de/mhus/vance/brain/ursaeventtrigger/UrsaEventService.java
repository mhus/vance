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
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.settings.SettingService;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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
     */
    public record UrsaEventTriggerResult(
            String workflowName,
            @Nullable String workflowRunId,
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

            UrsaEventTriggerResult result;
            try {
                result = executeAction(tenantId, projectId, eventName, event, payload, correlationId, firedAt);
            } catch (ResponseStatusException ex) {
                // executeAction already tagged the metric outcome; copy
                // it into our log tracking so the document mirrors the
                // metric vocab.
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
                                errorMessage));
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
            String correlationId, Instant firedAt) {

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

        String targetName;
        if (action instanceof TriggerAction.Recipe r) {
            targetName = r.recipe();
        } else if (action instanceof TriggerAction.Workflow w) {
            targetName = w.workflow();
        } else if (action instanceof TriggerAction.Script s) {
            targetName = "script:" + s.path();
        } else {
            targetName = eventName;
        }
        return new UrsaEventTriggerResult(targetName, result.spawnedId(), correlationId, firedAt);
    }

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

            UrsaEventTriggerResult result;
            try {
                result = executeAction(tenantId, projectId, eventName, event, payload, correlationId, firedAt);
            } catch (ResponseStatusException ex) {
                // Re-tag the metric outcome under the admin source.
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
                                errorMessage));
            }
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
    private static boolean constantTimeEquals(String expected, @Nullable String actual) {
        if (actual == null) return false;
        byte[] e = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] a = actual.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(e, a);
    }
}
