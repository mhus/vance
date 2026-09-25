package de.mhus.vance.brain.thinkengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessStatusChangedEvent;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link ParentNotificationListener}: the parent-side translation gate
 * (Live-Fund 8). The engine-output-translator exists for parents that
 * RELAY child output verbatim to a human (Arthur, Eddie). Composing
 * parents — LLM agents like Hactar's session identity — get the RAW
 * machine summary: the fast-tier translation is lossy and can misstate
 * machine facts (observed live: Slart persisted a script, the
 * translator rendered "the script was not written", the composing
 * parent believed it and re-spawned the author seven times).
 */
class ParentNotificationListenerTranslateGateTest {

    private ProcessEventEmitter eventEmitter;
    private ThinkProcessService thinkProcessService;
    private ThinkEngineService thinkEngineService;
    private ObjectProvider<ThinkEngineService> engineServiceProvider;
    private ObjectProvider<LightLlmService> lightLlmServiceProvider;
    private LightLlmService lightLlmService;
    private ParentNotificationListener listener;

    private ThinkProcessDocument child;
    private ThinkProcessDocument parent;
    private ThinkEngine childEngine;
    private ThinkEngine parentEngine;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        eventEmitter = mock(ProcessEventEmitter.class);
        thinkProcessService = mock(ThinkProcessService.class);
        thinkEngineService = mock(ThinkEngineService.class);
        engineServiceProvider = mock(ObjectProvider.class);
        when(engineServiceProvider.getObject()).thenReturn(thinkEngineService);
        lightLlmServiceProvider = mock(ObjectProvider.class);
        lightLlmService = mock(LightLlmService.class);
        when(lightLlmServiceProvider.getIfAvailable()).thenReturn(lightLlmService);

        StopInitiatorRegistry stopRegistry = mock(StopInitiatorRegistry.class);
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        listener = new ParentNotificationListener(
                eventEmitter,
                thinkProcessService,
                engineServiceProvider,
                stopRegistry,
                chatMessageService,
                lightLlmServiceProvider);

        child = new ThinkProcessDocument();
        child.setId("child-1");
        child.setTenantId("acme");
        child.setProjectId("p1");
        child.setSessionId("s1");
        child.setThinkEngine("hactar");
        child.setCloseReason(de.mhus.vance.api.thinkprocess.CloseReason.DONE);
        parent = new ThinkProcessDocument();
        parent.setId("parent-1");
        parent.setTenantId("acme");
        parent.setProjectId("p1");
        parent.setSessionId("s1");
        parent.setThinkEngine("hactar");

        when(thinkProcessService.findById("child-1")).thenReturn(Optional.of(child));
        when(thinkProcessService.findById("parent-1")).thenReturn(Optional.of(parent));
        when(chatMessageService.activeHistory(any(), any(), any())).thenReturn(List.of());

        childEngine = mock(ThinkEngine.class);
        when(childEngine.name()).thenReturn("hactar");
        when(childEngine.producesUserFacingOutput()).thenReturn(false);
        when(childEngine.summarizeForParent(any(), any()))
                .thenReturn(new ParentReport(
                        "raw machine summary: persisted at _vance/scripts/_slart/x/s.js",
                        Map.of("recipePath", "_vance/scripts/_slart/x/s.js")));
        parentEngine = mock(ThinkEngine.class);
        when(parentEngine.name()).thenReturn("hactar");
        when(thinkEngineService.resolveForProcess(child)).thenReturn(childEngine);
        when(thinkEngineService.resolveForProcess(parent)).thenReturn(parentEngine);

        when(eventEmitter.notifyParent(any(), any(), any(), any(), any(), any()))
                .thenReturn(true);
    }

    private ThinkProcessStatusChangedEvent doneEvent() {
        return new ThinkProcessStatusChangedEvent(
                "child-1", "acme", "s1", "parent-1", ThinkProcessStatus.RUNNING, ThinkProcessStatus.CLOSED);
    }

    @Test
    void composingParent_getsRawSummary_noTranslation() {
        when(parentEngine.relaysChildOutputVerbatim()).thenReturn(false);

        listener.onStatusChanged(doneEvent());

        verify(lightLlmService, never()).call(any());
        ArgumentCaptor<String> summaryCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventEmitter).notifyParent(any(), any(), any(), summaryCaptor.capture(), any(), any());
        // The raw engine summary — machine facts intact, no lossy rewrite.
        assertThat(summaryCaptor.getValue()).contains("raw machine summary: persisted at _vance/scripts/_slart/x/s.js");
    }

    @Test
    void relayingParent_getsTranslatedSummary() {
        when(parentEngine.relaysChildOutputVerbatim()).thenReturn(true);
        when(lightLlmService.call(any())).thenReturn("translated user-facing answer");

        listener.onStatusChanged(doneEvent());

        ArgumentCaptor<String> summaryCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventEmitter).notifyParent(any(), any(), any(), summaryCaptor.capture(), any(), any());
        assertThat(summaryCaptor.getValue()).isEqualTo("translated user-facing answer");
    }

    @Test
    void unresolvableParentEngine_fallsBackToTranslation() {
        when(thinkProcessService.findById("parent-1")).thenReturn(Optional.empty());

        listener.onStatusChanged(doneEvent());

        // Conservative fallback: unknown parent keeps the pre-gate behavior.
        verify(lightLlmService).call(any());
    }

    @Test
    void payloadRidesTheEvent_regardlessOfGate() {
        when(parentEngine.relaysChildOutputVerbatim()).thenReturn(false);

        listener.onStatusChanged(doneEvent());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventEmitter).notifyParent(any(), any(), any(), any(), payloadCaptor.capture(), any());
        assertThat(payloadCaptor.getValue()).containsEntry("recipePath", "_vance/scripts/_slart/x/s.js");
    }
}
