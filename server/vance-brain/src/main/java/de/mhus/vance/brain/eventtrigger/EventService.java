package de.mhus.vance.brain.eventtrigger;

import de.mhus.vance.brain.hactar.HactarWorkflowService;
import de.mhus.vance.shared.events.EventLoader;
import de.mhus.vance.shared.events.ResolvedEvent;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.settings.SettingService;
import java.time.Duration;
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
 * to {@link HactarWorkflowService}.
 *
 * <p>Lives in {@code vance-brain} because (a) it depends on
 * {@code HactarWorkflowService} which is brain-only and (b) the brain
 * is the only deployment surface that exposes the {@code /brain/...}
 * REST endpoints. {@code vance-anus} (pod runtime) does not see this
 * class, matching the user instruction not to wire workflow spawn
 * outside the brain.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventService {

    /** Reserved param key under which the request payload is exposed to the workflow. */
    public static final String PAYLOAD_PARAM_KEY = "payload";

    /** Micrometer counter for trigger calls. Tags: {@code event}, {@code outcome}. */
    private static final String METRIC_TRIGGERS = "vance.events.triggers";

    /** Micrometer timer for successful trigger latency. Tag: {@code event}. */
    private static final String METRIC_TRIGGER_DURATION = "vance.events.trigger.duration";

    private final EventLoader eventLoader;
    private final SettingService settingService;
    private final MetricService metricService;
    /** Optional — Hactar is feature-flagged; when off, events return 503. */
    private final ObjectProvider<HactarWorkflowService> workflowServiceProvider;

    /** Outcome of a successful event trigger — carries the spawned runId for the response body. */
    public record EventTriggerResult(String workflowName, String workflowRunId) {}

    /**
     * Trigger flow:
     * <ol>
     *   <li>resolve event via cascade ({@code project → _vance}) → 404</li>
     *   <li>{@code enabled: false} or method-not-allowed → 404 (don't leak existence)</li>
     *   <li>bearer auth check (when {@code auth:} block configured) → 401</li>
     *   <li>{@link HactarWorkflowService#start} (start fails → 502 / 400)</li>
     * </ol>
     *
     * <p>{@code payload} is nested under {@link #PAYLOAD_PARAM_KEY} in
     * the params handed to the workflow — see {@code specification/events.md} §4.
     */
    public EventTriggerResult trigger(
            String tenantId,
            String projectId,
            String eventName,
            String httpMethod,
            @Nullable String bearerToken,
            @Nullable Object payload) {
        long startNanos = System.nanoTime();

        ResolvedEvent event;
        try {
            event = eventLoader.load(tenantId, projectId, eventName)
                    .orElse(null);
        } catch (RuntimeException ex) {
            countOutcome(eventName, "bad_payload");
            throw ex;
        }
        if (event == null) {
            countOutcome(eventName, "not_found");
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Event '" + eventName + "' not found");
        }

        if (!event.enabled()) {
            // Treat disabled events as 404 — don't leak that the event
            // exists. Caller can flip `enabled: true` in the YAML to re-enable.
            log.debug("Event '{}/{}/{}' disabled — returning 404",
                    tenantId, projectId, eventName);
            countOutcome(eventName, "disabled");
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Event '" + eventName + "' not found");
        }

        if (!event.acceptsMethod(httpMethod)) {
            countOutcome(eventName, "method_not_allowed");
            throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED,
                    "Event '" + eventName + "' does not accept " + httpMethod);
        }

        if (event.requiresAuth()) {
            String expected = resolveExpectedToken(tenantId, projectId, event);
            if (expected == null || expected.isBlank()) {
                // Misconfiguration on the operator side — the YAML
                // references a setting that isn't set. Refuse the
                // request rather than silently auth-bypass.
                log.warn("Event '{}/{}/{}' requires auth but token is unresolved "
                                + "(setting '{}' empty)",
                        tenantId, projectId, eventName, event.tokenSettingKey());
                countOutcome(eventName, "auth_misconfigured");
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Event auth is misconfigured");
            }
            if (!constantTimeEquals(expected, bearerToken)) {
                countOutcome(eventName, "unauthorized");
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Invalid or missing bearer token");
            }
        }

        HactarWorkflowService workflowService = workflowServiceProvider.getIfAvailable();
        if (workflowService == null) {
            log.warn("Event '{}/{}/{}' wants workflow '{}' but Hactar is not active "
                            + "(vance.services.hactar=false)",
                    tenantId, projectId, eventName, event.workflow());
            countOutcome(eventName, "hactar_unavailable");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Hactar workflow subsystem is not active");
        }

        Map<String, Object> mergedParams = new LinkedHashMap<>(event.params());
        if (payload != null) {
            mergedParams.put(PAYLOAD_PARAM_KEY, payload);
        }

        String runId;
        try {
            runId = workflowService.start(
                    tenantId, projectId,
                    event.workflow(), mergedParams,
                    event.runAs() != null ? event.runAs() : event.createdBy());
        } catch (RuntimeException ex) {
            log.warn("Event '{}/{}/{}' workflow start failed: {}",
                    tenantId, projectId, eventName, ex.toString());
            countOutcome(eventName, "spawn_failed");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Workflow spawn failed: " + ex.getMessage(), ex);
        }

        countOutcome(eventName, "success");
        metricService.timer(METRIC_TRIGGER_DURATION, "event", eventName)
                .record(Duration.ofNanos(System.nanoTime() - startNanos));
        log.info("Event '{}/{}/{}' spawned workflow '{}' runId='{}'",
                tenantId, projectId, eventName, event.workflow(), runId);
        return new EventTriggerResult(event.workflow(), runId);
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
    public EventTriggerResult triggerAdmin(
            String tenantId,
            String projectId,
            String eventName,
            @Nullable Object payload,
            @Nullable String triggeredBy) {
        long startNanos = System.nanoTime();

        ResolvedEvent event = eventLoader.load(tenantId, projectId, eventName)
                .orElse(null);
        if (event == null) {
            countOutcomeAdmin(eventName, "not_found");
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Event '" + eventName + "' not found");
        }

        if (!event.enabled()) {
            countOutcomeAdmin(eventName, "disabled");
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Event '" + eventName + "' is disabled — flip enabled: true to trigger");
        }

        HactarWorkflowService workflowService = workflowServiceProvider.getIfAvailable();
        if (workflowService == null) {
            countOutcomeAdmin(eventName, "hactar_unavailable");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Hactar workflow subsystem is not active");
        }

        Map<String, Object> mergedParams = new LinkedHashMap<>(event.params());
        if (payload != null) {
            mergedParams.put(PAYLOAD_PARAM_KEY, payload);
        }

        String runAs = triggeredBy != null && !triggeredBy.isBlank()
                ? triggeredBy
                : (event.runAs() != null ? event.runAs() : event.createdBy());

        String runId;
        try {
            runId = workflowService.start(
                    tenantId, projectId,
                    event.workflow(), mergedParams, runAs);
        } catch (RuntimeException ex) {
            log.warn("Admin event trigger '{}/{}/{}' workflow start failed: {}",
                    tenantId, projectId, eventName, ex.toString());
            countOutcomeAdmin(eventName, "spawn_failed");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Workflow spawn failed: " + ex.getMessage(), ex);
        }

        countOutcomeAdmin(eventName, "success");
        metricService.timer(METRIC_TRIGGER_DURATION, "event", eventName)
                .record(Duration.ofNanos(System.nanoTime() - startNanos));
        log.info("Admin event '{}/{}/{}' spawned workflow '{}' runId='{}' triggeredBy='{}'",
                tenantId, projectId, eventName, event.workflow(), runId, runAs);
        return new EventTriggerResult(event.workflow(), runId);
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
            String tenantId, String projectId, ResolvedEvent event) {
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
