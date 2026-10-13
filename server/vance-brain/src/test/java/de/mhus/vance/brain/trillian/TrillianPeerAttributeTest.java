package de.mhus.vance.brain.trillian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.trillian.nature.TrillianNature;
import de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.enginemessage.EngineMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Control's attribute writes on the loop are single atomic entries: the loop
 * writes its task return addresses into the same {@code engineParams} from
 * its own lane, and a whole-map write-back from either side would roll the
 * other's back.
 */
class TrillianPeerAttributeTest {

    private final ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);
    private final TrillianNatureRegistry natureRegistry = mock(TrillianNatureRegistry.class);
    private final TrillianInternalApi api = new TrillianInternalApi(
            thinkProcessService,
            mock(EngineMessageRouter.class),
            mock(EngineMessageService.class),
            mock(de.mhus.vance.brain.thinkengine.ProcessEventEmitter.class),
            mock(ChatMessageService.class),
            mock(de.mhus.vance.brain.scheduling.LaneScheduler.class),
            natureRegistry);

    private final ThinkProcessDocument loop = new ThinkProcessDocument();

    @BeforeEach
    void setUp() {
        ThinkProcessDocument control = new ThinkProcessDocument();
        control.setId("control-1");
        control.setTenantId("acme");
        Map<String, Object> controlParams = new LinkedHashMap<>();
        controlParams.put(TrillianSessionBootstrapper.PARAM_PEER_PROCESS_ID, "loop-1");
        control.setEngineParams(controlParams);

        loop.setId("loop-1");
        loop.setTenantId("acme");
        Map<String, Object> loopParams = new LinkedHashMap<>();
        loopParams.put(
                TrillianInternalApi.PARAM_ATTRIBUTES, new LinkedHashMap<>(Map.of("persona", "dry", "lang", "de")));
        loopParams.put(TrillianInternalApi.PARAM_TASK_REPLIES, new LinkedHashMap<>(Map.of("task-1", "head-1")));
        loop.setEngineParams(loopParams);

        when(thinkProcessService.findById("control-1")).thenReturn(Optional.of(control));
        when(thinkProcessService.findById("loop-1")).thenReturn(Optional.of(loop));
        when(thinkProcessService.setEngineParamEntry(any(), any(), any(), any()))
                .thenReturn(true);
        when(natureRegistry.resolve(any())).thenReturn(mock(TrillianNature.class));
    }

    @Test
    void settingAnAttribute_writesOneEntry_withASanitisedKey() {
        assertThat(api.setPeerAttribute("control-1", "tone.of.voice", "terse")).isTrue();

        verify(thinkProcessService)
                .setEngineParamEntry("loop-1", TrillianInternalApi.PARAM_ATTRIBUTES, "tone_of_voice", "terse");
        verify(thinkProcessService, never()).replaceEngineParams(any(), any());
    }

    @Test
    void removingAnAttribute_unsetsOneEntry() {
        assertThat(api.removePeerAttribute("control-1", "persona")).isTrue();

        verify(thinkProcessService)
                .setEngineParamEntry("loop-1", TrillianInternalApi.PARAM_ATTRIBUTES, "persona", null);
        verify(thinkProcessService, never()).replaceEngineParams(any(), any());
    }

    @Test
    void removingAnAbsentAttribute_writesNothing() {
        assertThat(api.removePeerAttribute("control-1", "unknown")).isFalse();

        verify(thinkProcessService, never()).setEngineParamEntry(any(), any(), any(), any());
    }

    @Test
    void clearingAttributes_unsetsEachEntry_andLeavesTheTaskAddressesAlone() {
        assertThat(api.clearPeerAttributes("control-1")).isEqualTo(2);

        verify(thinkProcessService)
                .setEngineParamEntry("loop-1", TrillianInternalApi.PARAM_ATTRIBUTES, "persona", null);
        verify(thinkProcessService).setEngineParamEntry("loop-1", TrillianInternalApi.PARAM_ATTRIBUTES, "lang", null);
        verify(thinkProcessService, never()).replaceEngineParams(any(), any());
    }
}
