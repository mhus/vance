package de.mhus.vance.brain.ursaeventtrigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.ursaevents.EventSource;
import de.mhus.vance.brain.magrathea.MagratheaWorkflowService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.ursaevents.ResolvedUrsaEvent;
import de.mhus.vance.shared.ursaevents.UrsaEventLoader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Behavioural tests for {@link UrsaEventService}. Stubs the loader,
 * settings cascade, and workflow service — no Spring context, no
 * Mongo. Covers the full request flow: resolve, enabled, method,
 * bearer auth (literal + setting), workflow spawn, payload merging.
 */
class UrsaEventServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "p1";
    private static final String EVENT = "deploy";

    private UrsaEventLoader eventLoader;
    private de.mhus.vance.shared.project.ProjectService projectService;
    private SettingService settingService;
    private MetricService metricService;
    private MagratheaWorkflowService workflowService;

    @SuppressWarnings("unchecked")
    private final ObjectProvider<MagratheaWorkflowService> workflowProvider = mock(ObjectProvider.class);

    private de.mhus.vance.brain.action.ActionExecutorRegistry actionExecutorRegistry;
    private de.mhus.vance.brain.ursascheduler.SystemSessionResolver systemSessionResolver;
    private UrsaEventLogService eventLogService;
    private UrsaEventService service;
    private final UrsaEventRateLimiter rateLimiter = new UrsaEventRateLimiter();
    private final UrsaEventDeliveryCache deliveries = new UrsaEventDeliveryCache(java.time.Duration.ofMinutes(10));
    private ObjectProvider<de.mhus.vance.brain.project.ProjectLocator> locatorProvider;
    private ObjectProvider<de.mhus.vance.brain.project.ProjectManagerService> managerProvider;

    @BeforeEach
    void setUp() {
        eventLoader = mock(UrsaEventLoader.class);
        projectService = mock(de.mhus.vance.shared.project.ProjectService.class);
        settingService = mock(SettingService.class);
        metricService = new MetricService(new SimpleMeterRegistry());
        workflowService = mock(MagratheaWorkflowService.class);
        when(workflowProvider.getIfAvailable()).thenReturn(workflowService);
        // Stub registry that pretends to route workflow actions directly
        // through the legacy MagratheaWorkflowService — keeps the existing
        // tests close to the old assertion shape.
        actionExecutorRegistry = mock(de.mhus.vance.brain.action.ActionExecutorRegistry.class);
        when(actionExecutorRegistry.execute(any(), any(), any())).thenAnswer(inv -> {
            de.mhus.vance.api.action.TriggerAction action = inv.getArgument(0);
            if (action instanceof de.mhus.vance.api.action.TriggerAction.Workflow w) {
                String runId = workflowService.start(TENANT, PROJECT, w.workflow(), w.params(), w.runAs());
                return de.mhus.vance.brain.action.ActionResult.scheduled(runId);
            }
            if (action instanceof de.mhus.vance.api.action.TriggerAction.Recipe r) {
                return de.mhus.vance.brain.action.ActionResult.scheduled("proc-" + r.recipe());
            }
            return de.mhus.vance.brain.action.ActionResult.success(java.util.Map.of());
        });
        systemSessionResolver = mock(de.mhus.vance.brain.ursascheduler.SystemSessionResolver.class);
        de.mhus.vance.shared.session.SessionDocument session = new de.mhus.vance.shared.session.SessionDocument();
        session.setSessionId("sess-event");
        when(systemSessionResolver.resolve(any(), any(), any(), any())).thenReturn(session);
        // UrsaEventLogService is a thin diagnostics writer — mock it
        // so the existing assertions stay focussed on trigger
        // semantics and don't depend on a real DocumentService wiring.
        eventLogService = mock(UrsaEventLogService.class);
        // No locator and no project manager: these tests drive the trigger
        // semantics, and an absent locator means "run here", which is what the
        // podless and single-pod paths do anyway.
        @SuppressWarnings("unchecked")
        ObjectProvider<de.mhus.vance.brain.project.ProjectLocator> locators = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<de.mhus.vance.brain.project.ProjectManagerService> managers = mock(ObjectProvider.class);
        locatorProvider = locators;
        managerProvider = managers;
        service = newService(/*asyncMaxConcurrent*/ 32);
    }

    /** Same wiring as {@link #setUp()}, with the async ceiling as a knob. */
    private UrsaEventService newService(int asyncMaxConcurrent) {
        return new UrsaEventService(
                eventLoader,
                settingService,
                metricService,
                workflowProvider,
                actionExecutorRegistry,
                systemSessionResolver,
                eventLogService,
                locatorProvider,
                managerProvider,
                mock(UrsaEventForwarder.class),
                projectService,
                rateLimiter,
                deliveries,
                asyncMaxConcurrent,
                /*defaultRateLimitPerMinute*/ 0);
    }

    // ─── synchronous output ─────────────────────────────────────────────

    /** Makes every script action return {@code value}. */
    private void scriptReturns(Object value) {
        when(actionExecutorRegistry.execute(any(), any(), any())).thenAnswer(inv -> {
            de.mhus.vance.api.action.TriggerAction action = inv.getArgument(0);
            if (action instanceof de.mhus.vance.api.action.TriggerAction.Script) {
                return de.mhus.vance.brain.action.ActionResult.success(Map.of("value", value));
            }
            return de.mhus.vance.brain.action.ActionResult.scheduled("run-x");
        });
    }

    @Test
    void script_event_returns_its_result_to_the_caller() {
        // The value was always computed and mapped into ActionResult.output
        // — it just never left the service. No runAs: the script runs under
        // the event's own identity, so there is nothing to withhold.
        when(eventLoader.load(TENANT, PROJECT, EVENT))
                .thenReturn(
                        Optional.of(event(b -> b.script("_vance/scripts/x.js").runAs(null))));
        scriptReturns("Der Rat hat zugestimmt.");

        UrsaEventService.UrsaEventTriggerResult r = service.trigger(TENANT, PROJECT, EVENT, "POST", null, null);

        assertThat(r.output()).containsEntry("value", "Der Rat hat zugestimmt.");
        assertThat(r.workflowRunId()).isNull();
        assertThat(r.workflowName()).isEqualTo("script:_vance/scripts/x.js");
    }

    @Test
    void spawn_event_still_returns_an_id_and_no_output() {
        when(eventLoader.load(TENANT, PROJECT, EVENT)).thenReturn(Optional.of(event(b -> {})));
        when(workflowService.start(any(), any(), any(), any(), any())).thenReturn("run-123");

        UrsaEventService.UrsaEventTriggerResult r = service.trigger(TENANT, PROJECT, EVENT, "POST", null, null);

        assertThat(r.workflowRunId()).isEqualTo("run-123");
        assertThat(r.output()).isNull();
    }

    @Test
    void async_script_event_returns_immediately_without_output() {
        when(eventLoader.load(TENANT, PROJECT, EVENT))
                .thenReturn(Optional.of(
                        event(b -> b.script("_vance/scripts/slow.js").async(true))));
        scriptReturns("done");

        UrsaEventService.UrsaEventTriggerResult r = service.trigger(TENANT, PROJECT, EVENT, "POST", null, null);

        assertThat(r.output()).isNull();
        // The async task owns the single log write — same correlationId,
        // so the logPath the caller was handed still resolves.
        verify(eventLogService, org.mockito.Mockito.timeout(5000)).record(eq(r.correlationId()), any());
    }

    // ─── webhook hardening: signature, rate limit, delivery id ──────────

    private static final String SECRET = "s3cret";

    private static de.mhus.vance.shared.ursaevents.WebhookSignatureSpec githubStyle() {
        return new de.mhus.vance.shared.ursaevents.WebhookSignatureSpec(
                "X-Hub-Signature-256",
                "webhook.secret",
                "sha256=",
                de.mhus.vance.shared.ursaevents.WebhookSignatureSpec.Encoding.HEX,
                null,
                300);
    }

    private static String hmacHex(String secret, byte[] body) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(mac.doFinal(body));
    }

    private UrsaEventService.WebhookRequest request(byte[] body, Map<String, String> headers) {
        return new UrsaEventService.WebhookRequest(
                body,
                name -> headers.entrySet().stream()
                        .filter(e -> e.getKey().equalsIgnoreCase(name))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .orElse(null));
    }

    private UrsaEventService.UrsaEventTriggerResult fire(UrsaEventService.WebhookRequest webhook) {
        return service.trigger(TENANT, PROJECT, EVENT, "POST", null, null, false, webhook);
    }

    private void givenSecret() {
        when(settingService.getDecryptedPasswordCascade(eq(TENANT), eq(PROJECT), any(), eq("webhook.secret")))
                .thenReturn(SECRET);
    }

    @Test
    void a_correctly_signed_webhook_is_accepted() throws Exception {
        when(eventLoader.load(TENANT, PROJECT, EVENT)).thenReturn(Optional.of(event(b -> b.signature(githubStyle()))));
        givenSecret();
        when(workflowService.start(any(), any(), any(), any(), any())).thenReturn("run-1");
        byte[] body = "{\"action\":\"opened\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        UrsaEventService.UrsaEventTriggerResult r =
                fire(request(body, Map.of("X-Hub-Signature-256", "sha256=" + hmacHex(SECRET, body))));

        assertThat(r.workflowRunId()).isEqualTo("run-1");
    }

    @Test
    void a_webhook_with_a_wrong_missing_or_unverifiable_signature_is_refused() throws Exception {
        when(eventLoader.load(TENANT, PROJECT, EVENT)).thenReturn(Optional.of(event(b -> b.signature(githubStyle()))));
        givenSecret();
        byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        for (UrsaEventService.WebhookRequest bad : List.of(
                request(body, Map.of("X-Hub-Signature-256", "sha256=" + hmacHex("other", body))),
                request(body, Map.of()),
                request(body, Map.of("X-Hub-Signature-256", hmacHex(SECRET, body))))) {
            assertThatThrownBy(() -> fire(bad))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
        // A caller that gives no request at all cannot be checked, so it is refused too.
        assertThatThrownBy(() -> service.trigger(TENANT, PROJECT, EVENT, "POST", null, null))
                .isInstanceOf(ResponseStatusException.class);
        verify(workflowService, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void a_signature_event_without_a_secret_is_misconfigured_not_open() {
        when(eventLoader.load(TENANT, PROJECT, EVENT)).thenReturn(Optional.of(event(b -> b.signature(githubStyle()))));

        assertThatThrownBy(() -> fire(request(new byte[0], Map.of())))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void an_event_over_its_rate_limit_answers_429_with_retry_after() {
        when(eventLoader.load(TENANT, PROJECT, EVENT)).thenReturn(Optional.of(event(b -> b.rateLimit(2))));
        when(workflowService.start(any(), any(), any(), any(), any())).thenReturn("run-1");

        service.trigger(TENANT, PROJECT, EVENT, "POST", null, null);
        service.trigger(TENANT, PROJECT, EVENT, "POST", null, null);

        assertThatThrownBy(() -> service.trigger(TENANT, PROJECT, EVENT, "POST", null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(429);
                    assertThat(ex.getHeaders().getFirst("Retry-After")).isNotNull();
                });
        verify(workflowService, org.mockito.Mockito.times(2)).start(any(), any(), any(), any(), any());
    }

    @Test
    void a_delivery_the_provider_sends_again_is_answered_without_running_again() {
        when(eventLoader.load(TENANT, PROJECT, EVENT))
                .thenReturn(Optional.of(event(b -> b.idempotencyHeader("X-Delivery"))));
        when(workflowService.start(any(), any(), any(), any(), any())).thenReturn("run-1");
        var request = request(new byte[0], Map.of("X-Delivery", "d-1"));

        UrsaEventService.UrsaEventTriggerResult first = fire(request);
        UrsaEventService.UrsaEventTriggerResult again = fire(request);

        assertThat(again.workflowRunId()).isEqualTo(first.workflowRunId());
        verify(workflowService, org.mockito.Mockito.times(1)).start(any(), any(), any(), any(), any());
        // A different delivery is a different run.
        fire(request(new byte[0], Map.of("X-Delivery", "d-2")));
        verify(workflowService, org.mockito.Mockito.times(2)).start(any(), any(), any(), any(), any());
    }

    @Test
    void a_delivery_whose_first_try_failed_may_be_tried_again() {
        when(eventLoader.load(TENANT, PROJECT, EVENT))
                .thenReturn(Optional.of(event(b -> b.idempotencyHeader("X-Delivery"))));
        when(workflowService.start(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("boom"))
                .thenReturn("run-2");
        var request = request(new byte[0], Map.of("X-Delivery", "d-1"));

        assertThatThrownBy(() -> fire(request)).isInstanceOf(RuntimeException.class);
        UrsaEventService.UrsaEventTriggerResult retried = fire(request);

        assertThat(retried.workflowRunId()).isEqualTo("run-2");
    }

    // ─── stopped projects ───────────────────────────────────────────────

    private void givenProjectIn(de.mhus.vance.shared.project.ProjectStatus status) {
        when(projectService.findByTenantAndName(TENANT, PROJECT))
                .thenReturn(Optional.of(de.mhus.vance.shared.project.ProjectDocument.builder()
                        .tenantId(TENANT)
                        .name(PROJECT)
                        .status(status)
                        .build()));
    }

    @Test
    void an_event_does_not_start_an_idle_project() {
        // An idle project is idle because its users stayed away; a webhook must
        // not undo that. Only a person opening the project wakes it.
        when(eventLoader.load(TENANT, PROJECT, EVENT)).thenReturn(Optional.of(event(b -> {})));
        givenProjectIn(de.mhus.vance.shared.project.ProjectStatus.IDLE);

        assertThatThrownBy(() -> service.trigger(TENANT, PROJECT, EVENT, "POST", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);

        verify(workflowService, org.mockito.Mockito.never()).start(any(), any(), any(), any(), any());
        verify(locatorProvider, org.mockito.Mockito.never()).getIfAvailable();
    }

    @Test
    void an_event_does_not_start_a_suspended_project_either() {
        when(eventLoader.load(TENANT, PROJECT, EVENT)).thenReturn(Optional.of(event(b -> {})));
        givenProjectIn(de.mhus.vance.shared.project.ProjectStatus.SUSPENDED);

        assertThatThrownBy(() -> service.trigger(TENANT, PROJECT, EVENT, "POST", null, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void an_event_still_runs_in_a_project_that_wants_to_run() {
        when(eventLoader.load(TENANT, PROJECT, EVENT)).thenReturn(Optional.of(event(b -> {})));
        when(workflowService.start(any(), any(), any(), any(), any())).thenReturn("run-9");
        givenProjectIn(de.mhus.vance.shared.project.ProjectStatus.RUNNING);

        UrsaEventService.UrsaEventTriggerResult r = service.trigger(TENANT, PROJECT, EVENT, "POST", null, null);

        assertThat(r.workflowRunId()).isEqualTo("run-9");
    }

    // ─── async admission control ────────────────────────────────────────

    @Test
    void async_script_events_are_rejected_once_every_slot_is_taken() throws Exception {
        // The event route carries no JWT and no rate limit. While the script
        // ran on the request thread the servlet pool was an implicit ceiling;
        // answering immediately removed it, so the ceiling has to be explicit.
        UrsaEventService bounded = newService(/*asyncMaxConcurrent*/ 1);
        when(eventLoader.load(TENANT, PROJECT, EVENT))
                .thenReturn(Optional.of(
                        event(b -> b.script("_vance/scripts/slow.js").async(true))));
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(actionExecutorRegistry.execute(any(), any(), any())).thenAnswer(inv -> {
            started.countDown();
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return de.mhus.vance.brain.action.ActionResult.success(Map.of("value", "done"));
        });

        try {
            bounded.trigger(TENANT, PROJECT, EVENT, "POST", null, null);
            assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> bounded.trigger(TENANT, PROJECT, EVENT, "POST", null, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void an_async_slot_comes_back_when_the_script_finishes() throws Exception {
        UrsaEventService bounded = newService(/*asyncMaxConcurrent*/ 1);
        when(eventLoader.load(TENANT, PROJECT, EVENT))
                .thenReturn(Optional.of(
                        event(b -> b.script("_vance/scripts/quick.js").async(true))));
        scriptReturns("done");

        bounded.trigger(TENANT, PROJECT, EVENT, "POST", null, null);

        // The slot is released after the task body, so poll rather than
        // assume an ordering between the task and this thread.
        UrsaEventService.UrsaEventTriggerResult second = null;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (second == null) {
            try {
                second = bounded.trigger(TENANT, PROJECT, EVENT, "POST", null, null);
            } catch (ResponseStatusException ex) {
                if (System.nanoTime() > deadline) throw ex;
                Thread.sleep(20);
            }
        }

        assertThat(second.correlationId()).isNotBlank();
    }

    // ─── output visibility on the bypass surface ────────────────────────

    @Test
    void agent_trigger_withholds_output_when_the_event_crosses_identity() {
        // runAs set, outputToAgents unstated: event_fire skipped the bearer
        // check, so it does not get the result of privileged work.
        when(eventLoader.load(TENANT, PROJECT, EVENT))
                .thenReturn(
                        Optional.of(event(b -> b.script("_vance/scripts/x.js").runAs("ci-bot"))));
        scriptReturns("secret");

        UrsaEventService.UrsaEventTriggerResult r = service.triggerAdmin(TENANT, PROJECT, EVENT, null, "agent:marvin");

        assertThat(r.output()).isNull();
    }

    @Test
    void agent_trigger_gets_output_when_the_event_opts_in() {
        when(eventLoader.load(TENANT, PROJECT, EVENT))
                .thenReturn(Optional.of(event(
                        b -> b.script("_vance/scripts/x.js").runAs("ci-bot").outputToAgents(true))));
        scriptReturns("shared");

        UrsaEventService.UrsaEventTriggerResult r = service.triggerAdmin(TENANT, PROJECT, EVENT, null, "agent:marvin");

        assertThat(r.output()).containsEntry("value", "shared");
    }

    @Test
    void public_webhook_withholds_output_when_the_event_crosses_identity() {
        // auth.public + runAs is the inverted case: the log document was
        // already redacted while the anonymous HTTP caller got the full
        // return value of the privileged script.
        when(eventLoader.load(TENANT, PROJECT, EVENT))
                .thenReturn(
                        Optional.of(event(b -> b.script("_vance/scripts/x.js").runAs("ci-bot"))));
        scriptReturns("secret");

        UrsaEventService.UrsaEventTriggerResult r = service.trigger(TENANT, PROJECT, EVENT, "POST", null, null);

        assertThat(r.output()).isNull();
        assertThat(r.workflowName()).isEqualTo("script:_vance/scripts/x.js");
    }

    @Test
    void public_webhook_gets_output_when_the_event_opts_in() {
        when(eventLoader.load(TENANT, PROJECT, EVENT))
                .thenReturn(Optional.of(event(
                        b -> b.script("_vance/scripts/x.js").runAs("ci-bot").outputToAgents(true))));
        scriptReturns("shared");

        UrsaEventService.UrsaEventTriggerResult r = service.trigger(TENANT, PROJECT, EVENT, "POST", null, null);

        assertThat(r.output()).containsEntry("value", "shared");
    }

    @Test
    void authenticated_webhook_gets_output_even_when_agents_may_not() {
        // The token holder proved authorisation; the restriction is about
        // the surface that skips that check, not about runAs as such.
        when(eventLoader.load(TENANT, PROJECT, EVENT))
                .thenReturn(Optional.of(event(
                        b -> b.script("_vance/scripts/x.js").runAs("ci-bot").tokenLiteral("s3cret"))));
        scriptReturns("payload");

        UrsaEventService.UrsaEventTriggerResult r = service.trigger(TENANT, PROJECT, EVENT, "POST", "s3cret", null);

        assertThat(r.output()).containsEntry("value", "payload");
    }

    @Test
    void withheld_output_is_not_leaked_through_the_log_document() {
        // The log lives in the project's document layer, so an agent could
        // read it. Recording the value there would make the control
        // decorative.
        assertThat(UrsaEventService.summariseOutput(Map.of("value", "secret"), false))
                .isEqualTo("[withheld — outputToAgents: false]");
        assertThat(UrsaEventService.summariseOutput(Map.of("value", "secret"), true))
                .contains("secret");
    }

    @Test
    void logged_output_is_truncated_and_says_so() {
        String big = "x".repeat(5000);

        String summary = UrsaEventService.summariseOutput(Map.of("value", big), true);

        assertThat(summary).contains("truncated").hasSizeLessThan(3000);
    }

    // ─── happy path ─────────────────────────────────────────────────────

    @Test
    void trigger_unauthenticated_event_spawns_workflow_and_returns_runId() {
        when(eventLoader.load(TENANT, PROJECT, EVENT)).thenReturn(Optional.of(event(builder -> {})));
        when(workflowService.start(eq(TENANT), eq(PROJECT), eq("w-deploy"), any(), any()))
                .thenReturn("run-123");

        UrsaEventService.UrsaEventTriggerResult r = service.trigger(TENANT, PROJECT, EVENT, "POST", null, null);

        assertThat(r.workflowName()).isEqualTo("w-deploy");
        assertThat(r.workflowRunId()).isEqualTo("run-123");
    }

    @Test
    void trigger_passes_payload_under_payload_param_key() {
        when(eventLoader.load(TENANT, PROJECT, EVENT)).thenReturn(Optional.of(event(b -> {})));
        when(workflowService.start(any(), any(), any(), any(), any())).thenReturn("run-x");

        Map<String, Object> payload = Map.of("branch", "main", "ref", "abc123");
        service.trigger(TENANT, PROJECT, EVENT, "POST", null, payload);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCap = ArgumentCaptor.forClass(Map.class);
        verify(workflowService).start(any(), any(), any(), paramsCap.capture(), any());
        assertThat(paramsCap.getValue()).containsEntry(UrsaEventService.PAYLOAD_PARAM_KEY, payload);
    }

    @Test
    void trigger_merges_static_params_with_payload() {
        ResolvedUrsaEvent ev = event(b -> b.params(Map.of("env", "prod")));
        when(eventLoader.load(TENANT, PROJECT, EVENT)).thenReturn(Optional.of(ev));
        when(workflowService.start(any(), any(), any(), any(), any())).thenReturn("run-x");

        service.trigger(TENANT, PROJECT, EVENT, "POST", null, Map.of("k", "v"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCap = ArgumentCaptor.forClass(Map.class);
        verify(workflowService).start(any(), any(), any(), paramsCap.capture(), any());
        Map<String, Object> merged = paramsCap.getValue();
        assertThat(merged).containsEntry("env", "prod");
        assertThat(merged).containsEntry(UrsaEventService.PAYLOAD_PARAM_KEY, Map.of("k", "v"));
    }

    @Test
    void trigger_uses_runAs_then_createdBy_fallback() {
        ResolvedUrsaEvent ev = event(b -> b.runAs(null).createdBy("alice"));
        when(eventLoader.load(TENANT, PROJECT, EVENT)).thenReturn(Optional.of(ev));
        when(workflowService.start(any(), any(), any(), any(), any())).thenReturn("run-x");

        service.trigger(TENANT, PROJECT, EVENT, "GET", null, null);

        verify(workflowService).start(eq(TENANT), eq(PROJECT), eq("w-deploy"), any(), eq("alice"));
    }

    // ─── 404s ────────────────────────────────────────────────────────────

    @Test
    void trigger_unknown_event_returns_404() {
        when(eventLoader.load(TENANT, PROJECT, EVENT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.trigger(TENANT, PROJECT, EVENT, "GET", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void trigger_disabled_event_returns_404() {
        when(eventLoader.load(TENANT, PROJECT, EVENT)).thenReturn(Optional.of(event(b -> b.enabled(false))));

        assertThatThrownBy(() -> service.trigger(TENANT, PROJECT, EVENT, "GET", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        verify(workflowService, never()).start(any(), any(), any(), any(), any());
    }

    // ─── method allow-list ───────────────────────────────────────────────

    @Test
    void trigger_wrong_method_returns_405() {
        when(eventLoader.load(TENANT, PROJECT, EVENT)).thenReturn(Optional.of(event(b -> b.methods(Set.of("POST")))));

        assertThatThrownBy(() -> service.trigger(TENANT, PROJECT, EVENT, "GET", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    // ─── bearer auth ─────────────────────────────────────────────────────

    @Test
    void trigger_with_literal_token_match_succeeds() {
        when(eventLoader.load(TENANT, PROJECT, EVENT)).thenReturn(Optional.of(event(b -> b.tokenLiteral("hunter2"))));
        when(workflowService.start(any(), any(), any(), any(), any())).thenReturn("run-x");

        service.trigger(TENANT, PROJECT, EVENT, "GET", "hunter2", null);

        verify(workflowService, times(1)).start(any(), any(), any(), any(), any());
    }

    @Test
    void trigger_with_literal_token_mismatch_returns_401() {
        when(eventLoader.load(TENANT, PROJECT, EVENT)).thenReturn(Optional.of(event(b -> b.tokenLiteral("hunter2"))));

        assertThatThrownBy(() -> service.trigger(TENANT, PROJECT, EVENT, "GET", "wrong", null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(workflowService, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void trigger_missing_bearer_when_required_returns_401() {
        when(eventLoader.load(TENANT, PROJECT, EVENT)).thenReturn(Optional.of(event(b -> b.tokenLiteral("hunter2"))));

        assertThatThrownBy(() -> service.trigger(TENANT, PROJECT, EVENT, "GET", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void trigger_with_settingKey_resolves_through_cascade() {
        when(eventLoader.load(TENANT, PROJECT, EVENT))
                .thenReturn(Optional.of(event(b -> b.tokenSettingKey("ev.deploy.token"))));
        when(settingService.getStringValueCascade(TENANT, PROJECT, null, "ev.deploy.token"))
                .thenReturn("from-settings");
        when(workflowService.start(any(), any(), any(), any(), any())).thenReturn("run-x");

        service.trigger(TENANT, PROJECT, EVENT, "GET", "from-settings", null);

        verify(settingService).getStringValueCascade(TENANT, PROJECT, null, "ev.deploy.token");
        verify(workflowService, times(1)).start(any(), any(), any(), any(), any());
    }

    @Test
    void trigger_with_settingKey_but_setting_empty_returns_503() {
        when(eventLoader.load(TENANT, PROJECT, EVENT))
                .thenReturn(Optional.of(event(b -> b.tokenSettingKey("ev.deploy.token"))));
        when(settingService.getStringValueCascade(TENANT, PROJECT, null, "ev.deploy.token"))
                .thenReturn(null);

        assertThatThrownBy(() -> service.trigger(TENANT, PROJECT, EVENT, "GET", "anything", null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // ─── feature flag ────────────────────────────────────────────────────

    @Test
    void trigger_without_magrathea_returns_503() {
        when(workflowProvider.getIfAvailable()).thenReturn(null);
        when(eventLoader.load(TENANT, PROJECT, EVENT)).thenReturn(Optional.of(event(b -> {})));

        assertThatThrownBy(() -> service.trigger(TENANT, PROJECT, EVENT, "GET", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    /** Builder-style helper: defaults are filled in, callers override what they care about. */
    private static ResolvedUrsaEvent event(java.util.function.Consumer<EventBuilder> tweaks) {
        EventBuilder b = new EventBuilder();
        tweaks.accept(b);
        return b.build();
    }

    /**
     * Mutable bag of all {@link ResolvedUrsaEvent} fields with sensible
     * defaults. Local to the test — production code uses the record
     * constructor directly.
     */
    private static class EventBuilder {
        String name = EVENT;
        String yaml = "workflow: w-deploy\n";
        EventSource source = EventSource.PROJECT;
        String documentId = "doc-1";
        String createdBy = "operator";
        String description = null;
        String workflow = "w-deploy";
        boolean enabled = true;
        Set<String> methods = Set.of();
        String tokenLiteral = null;
        String tokenSettingKey = null;
        boolean authPublic = true;
        Map<String, Object> params = new LinkedHashMap<>();
        String runAs = "ci-bot";
        Boolean async = null;
        Boolean outputToAgents = null;
        List<String> tags = List.of();
        de.mhus.vance.shared.ursaevents.WebhookSignatureSpec signature = null;
        Integer rateLimitPerMinute = null;
        String idempotencyHeader = null;
        de.mhus.vance.shared.ursascheduler.ResolvedUrsaScheduler.ScriptSpec script = null;

        /** Switches the event from a workflow spawn to a script run. */
        EventBuilder script(String path) {
            this.script = new de.mhus.vance.shared.ursascheduler.ResolvedUrsaScheduler.ScriptSpec(
                    de.mhus.vance.api.action.ScriptSource.DOCUMENT, null, path, null);
            this.workflow = null;
            return this;
        }

        EventBuilder signature(de.mhus.vance.shared.ursaevents.WebhookSignatureSpec v) {
            this.signature = v;
            this.authPublic = false;
            return this;
        }

        EventBuilder rateLimit(int perMinute) {
            this.rateLimitPerMinute = perMinute;
            return this;
        }

        EventBuilder idempotencyHeader(String v) {
            this.idempotencyHeader = v;
            return this;
        }

        EventBuilder async(Boolean v) {
            this.async = v;
            return this;
        }

        EventBuilder enabled(boolean v) {
            this.enabled = v;
            return this;
        }

        EventBuilder methods(Set<String> v) {
            this.methods = v;
            return this;
        }

        EventBuilder tokenLiteral(String v) {
            this.tokenLiteral = v;
            this.authPublic = false;
            return this;
        }

        EventBuilder tokenSettingKey(String v) {
            this.tokenSettingKey = v;
            this.authPublic = false;
            return this;
        }

        EventBuilder params(Map<String, Object> v) {
            this.params = new LinkedHashMap<>(v);
            return this;
        }

        EventBuilder runAs(String v) {
            this.runAs = v;
            return this;
        }

        EventBuilder createdBy(String v) {
            this.createdBy = v;
            return this;
        }

        EventBuilder outputToAgents(Boolean v) {
            this.outputToAgents = v;
            return this;
        }

        ResolvedUrsaEvent build() {
            return new ResolvedUrsaEvent(
                    name,
                    yaml,
                    source,
                    documentId,
                    createdBy,
                    description,
                    /*recipe*/ null,
                    workflow,
                    script, /*initialMessage*/
                    null,
                    enabled,
                    methods,
                    tokenLiteral,
                    tokenSettingKey,
                    authPublic,
                    params,
                    runAs,
                    async,
                    outputToAgents,
                    tags,
                    signature,
                    rateLimitPerMinute,
                    idempotencyHeader);
        }
    }
}
