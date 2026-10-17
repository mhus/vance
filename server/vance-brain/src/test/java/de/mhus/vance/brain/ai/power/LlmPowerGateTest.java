package de.mhus.vance.brain.ai.power;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.shared.llmusage.CallAttribution;
import de.mhus.vance.shared.llmusage.LlmPowerState;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@link LlmPowerGate}: tenant switch for everyone, user switch for the call's owner, no exemptions. */
class LlmPowerGateTest {

    private static final String TENANT = "acme";
    private static final LlmPowerState.Switch OFF = new LlmPowerState.Switch(null, null);

    private final LlmPowerSwitch powerSwitch = mock(LlmPowerSwitch.class);
    private final SessionService sessionService = mock(SessionService.class);
    private final ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);
    private final LlmPowerGate gate = new LlmPowerGate(powerSwitch, sessionService, thinkProcessService);
    private final ModelInfo model = mock(ModelInfo.class);

    @Test
    void allOn_admits_withoutLookingUpTheUser() {
        when(powerSwitch.state(TENANT)).thenReturn(LlmPowerState.ALL_ON);

        assertThatCode(() -> gate.admit(attribution("sess-1", "proc-1", null), model))
                .doesNotThrowAnyException();
        verifyNoInteractions(sessionService, thinkProcessService);
    }

    @Test
    void tenantOff_refusesEveryCall_evenWithoutAUser() {
        when(powerSwitch.state(TENANT)).thenReturn(new LlmPowerState(OFF, Map.of()));

        assertThatThrownBy(() -> gate.admit(new CallAttribution(TENANT, null, null, null, "_compaction", null), model))
                .isInstanceOf(LlmPowerOffException.class)
                .hasMessageContaining("switched off for this tenant");
    }

    @Test
    void userOff_refusesTheCallOfThatUser_namedDirectly() {
        when(powerSwitch.state(TENANT)).thenReturn(new LlmPowerState(null, Map.of("road.runner", OFF)));

        assertThatThrownBy(() -> gate.admit(attribution(null, null, "road.runner"), model))
                .isInstanceOf(LlmPowerOffException.class)
                .hasMessageContaining("'road.runner'");
        assertThatCode(() -> gate.admit(attribution(null, null, "wile.coyote"), model))
                .doesNotThrowAnyException();
    }

    @Test
    void userOff_resolvesTheOwnerThroughTheSession_once() {
        when(powerSwitch.state(TENANT)).thenReturn(new LlmPowerState(null, Map.of("road.runner", OFF)));
        when(sessionService.findBySessionId("sess-1")).thenReturn(Optional.of(session("road.runner")));

        assertThat(gate.refusal(TENANT, null, "sess-1", null)).isNotNull();
        assertThat(gate.refusal(TENANT, null, "sess-1", null)).isNotNull();

        verify(sessionService, times(1)).findBySessionId("sess-1");
    }

    @Test
    void userOff_resolvesTheOwnerThroughTheProcess() {
        when(powerSwitch.state(TENANT)).thenReturn(new LlmPowerState(null, Map.of("road.runner", OFF)));
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setSessionId("sess-2");
        when(thinkProcessService.findById("proc-2")).thenReturn(Optional.of(process));
        when(sessionService.findBySessionId("sess-2")).thenReturn(Optional.of(session("road.runner")));

        assertThat(gate.refusal(TENANT, null, null, "proc-2")).contains("road.runner");
    }

    @Test
    void userOff_unknownOwner_isAdmitted_andNotRemembered() {
        when(powerSwitch.state(TENANT)).thenReturn(new LlmPowerState(null, Map.of("road.runner", OFF)));
        when(sessionService.findBySessionId("sess-3")).thenReturn(Optional.empty());

        assertThat(gate.refusal(TENANT, null, "sess-3", null)).isNull();
        assertThat(gate.refusal(TENANT, null, "sess-3", null)).isNull();

        verify(sessionService, times(2)).findBySessionId("sess-3");
    }

    private static CallAttribution attribution(String sessionId, String processId, String userId) {
        return new CallAttribution(TENANT, "p1", sessionId, processId, "arthur", "arthur", userId);
    }

    private static SessionDocument session(String userId) {
        SessionDocument session = new SessionDocument();
        session.setUserId(userId);
        return session;
    }
}
