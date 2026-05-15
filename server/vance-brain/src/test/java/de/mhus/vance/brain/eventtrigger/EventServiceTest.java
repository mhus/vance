package de.mhus.vance.brain.eventtrigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.events.EventSource;
import de.mhus.vance.brain.hactar.HactarWorkflowService;
import de.mhus.vance.shared.events.EventLoader;
import de.mhus.vance.shared.events.ResolvedEvent;
import de.mhus.vance.shared.settings.SettingService;
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
 * Behavioural tests for {@link EventService}. Stubs the loader,
 * settings cascade, and workflow service — no Spring context, no
 * Mongo. Covers the full request flow: resolve, enabled, method,
 * bearer auth (literal + setting), workflow spawn, payload merging.
 */
class EventServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "p1";
    private static final String EVENT = "deploy";

    private EventLoader eventLoader;
    private SettingService settingService;
    private HactarWorkflowService workflowService;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<HactarWorkflowService> workflowProvider = mock(ObjectProvider.class);
    private EventService service;

    @BeforeEach
    void setUp() {
        eventLoader = mock(EventLoader.class);
        settingService = mock(SettingService.class);
        workflowService = mock(HactarWorkflowService.class);
        when(workflowProvider.getIfAvailable()).thenReturn(workflowService);
        service = new EventService(eventLoader, settingService, workflowProvider);
    }

    // ─── happy path ─────────────────────────────────────────────────────

    @Test
    void trigger_unauthenticated_event_spawns_workflow_and_returns_runId() {
        when(eventLoader.load(TENANT, PROJECT, EVENT)).thenReturn(Optional.of(event(builder -> {})));
        when(workflowService.start(eq(TENANT), eq(PROJECT), eq("w-deploy"), any(), any()))
                .thenReturn("run-123");

        EventService.EventTriggerResult r = service.trigger(
                TENANT, PROJECT, EVENT, "POST", null, null);

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
        assertThat(paramsCap.getValue())
                .containsEntry(EventService.PAYLOAD_PARAM_KEY, payload);
    }

    @Test
    void trigger_merges_static_params_with_payload() {
        ResolvedEvent ev = event(b -> b.params(Map.of("env", "prod")));
        when(eventLoader.load(TENANT, PROJECT, EVENT)).thenReturn(Optional.of(ev));
        when(workflowService.start(any(), any(), any(), any(), any())).thenReturn("run-x");

        service.trigger(TENANT, PROJECT, EVENT, "POST", null, Map.of("k", "v"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCap = ArgumentCaptor.forClass(Map.class);
        verify(workflowService).start(any(), any(), any(), paramsCap.capture(), any());
        Map<String, Object> merged = paramsCap.getValue();
        assertThat(merged).containsEntry("env", "prod");
        assertThat(merged).containsEntry(EventService.PAYLOAD_PARAM_KEY, Map.of("k", "v"));
    }

    @Test
    void trigger_uses_runAs_then_createdBy_fallback() {
        ResolvedEvent ev = event(b -> b.runAs(null).createdBy("alice"));
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
        when(eventLoader.load(TENANT, PROJECT, EVENT))
                .thenReturn(Optional.of(event(b -> b.enabled(false))));

        assertThatThrownBy(() -> service.trigger(TENANT, PROJECT, EVENT, "GET", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        verify(workflowService, never()).start(any(), any(), any(), any(), any());
    }

    // ─── method allow-list ───────────────────────────────────────────────

    @Test
    void trigger_wrong_method_returns_405() {
        when(eventLoader.load(TENANT, PROJECT, EVENT))
                .thenReturn(Optional.of(event(b -> b.methods(Set.of("POST")))));

        assertThatThrownBy(() -> service.trigger(TENANT, PROJECT, EVENT, "GET", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    // ─── bearer auth ─────────────────────────────────────────────────────

    @Test
    void trigger_with_literal_token_match_succeeds() {
        when(eventLoader.load(TENANT, PROJECT, EVENT))
                .thenReturn(Optional.of(event(b -> b.tokenLiteral("hunter2"))));
        when(workflowService.start(any(), any(), any(), any(), any())).thenReturn("run-x");

        service.trigger(TENANT, PROJECT, EVENT, "GET", "hunter2", null);

        verify(workflowService, times(1)).start(any(), any(), any(), any(), any());
    }

    @Test
    void trigger_with_literal_token_mismatch_returns_401() {
        when(eventLoader.load(TENANT, PROJECT, EVENT))
                .thenReturn(Optional.of(event(b -> b.tokenLiteral("hunter2"))));

        assertThatThrownBy(() -> service.trigger(TENANT, PROJECT, EVENT, "GET", "wrong", null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(workflowService, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void trigger_missing_bearer_when_required_returns_401() {
        when(eventLoader.load(TENANT, PROJECT, EVENT))
                .thenReturn(Optional.of(event(b -> b.tokenLiteral("hunter2"))));

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
    void trigger_without_hactar_returns_503() {
        when(workflowProvider.getIfAvailable()).thenReturn(null);
        when(eventLoader.load(TENANT, PROJECT, EVENT)).thenReturn(Optional.of(event(b -> {})));

        assertThatThrownBy(() -> service.trigger(TENANT, PROJECT, EVENT, "GET", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    /** Builder-style helper: defaults are filled in, callers override what they care about. */
    private static ResolvedEvent event(java.util.function.Consumer<EventBuilder> tweaks) {
        EventBuilder b = new EventBuilder();
        tweaks.accept(b);
        return b.build();
    }

    /**
     * Mutable bag of all {@link ResolvedEvent} fields with sensible
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
        Map<String, Object> params = new LinkedHashMap<>();
        String runAs = "ci-bot";
        List<String> tags = List.of();

        EventBuilder enabled(boolean v) { this.enabled = v; return this; }
        EventBuilder methods(Set<String> v) { this.methods = v; return this; }
        EventBuilder tokenLiteral(String v) { this.tokenLiteral = v; return this; }
        EventBuilder tokenSettingKey(String v) { this.tokenSettingKey = v; return this; }
        EventBuilder params(Map<String, Object> v) { this.params = new LinkedHashMap<>(v); return this; }
        EventBuilder runAs(String v) { this.runAs = v; return this; }
        EventBuilder createdBy(String v) { this.createdBy = v; return this; }

        ResolvedEvent build() {
            return new ResolvedEvent(name, yaml, source, documentId, createdBy,
                    description, workflow, enabled, methods,
                    tokenLiteral, tokenSettingKey, params, runAs, tags);
        }
    }
}
