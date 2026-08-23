package de.mhus.vance.foot.remote;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.foot.config.FootConfig;
import org.junit.jupiter.api.Test;

/**
 * The local authorization gate. Attaching to a foot is effectively shell access
 * to the machine it runs on, so the interesting cases here are all about a
 * mistake never <em>widening</em> access.
 */
class RemoteControlGateTest {

    private RemoteControlGate gate(String mode) {
        FootConfig config = new FootConfig();
        config.getRemote().setMode(mode);
        return new RemoteControlGate(config);
    }

    @Test
    void defaultMode_announcesButRefusesInput() {
        RemoteControlGate gate = gate("ask");

        assertThat(gate.isEnabled()).isTrue();
        assertThat(gate.isInputAllowed()).isFalse();
    }

    @Test
    void allowMode_acceptsInputImmediately() {
        RemoteControlGate gate = gate("allow");

        assertThat(gate.isEnabled()).isTrue();
        assertThat(gate.isInputAllowed()).isTrue();
    }

    @Test
    void offMode_isInvisibleAndUnapprovable() {
        RemoteControlGate gate = gate("off");

        assertThat(gate.isEnabled()).isFalse();
        assertThat(gate.approve())
                .as("approving input on a client that never announces is a contradiction")
                .isFalse();
        assertThat(gate.isInputAllowed()).isFalse();
    }

    @Test
    void unknownMode_fallsBackToAskNeverToAllow() {
        RemoteControlGate gate = gate("allowed-please");

        assertThat(gate.mode()).isEqualTo(RemoteControlGate.MODE_ASK);
        assertThat(gate.isInputAllowed()).isFalse();
    }

    @Test
    void nullMode_fallsBackToAsk() {
        FootConfig config = new FootConfig();
        config.getRemote().setMode(null);

        assertThat(new RemoteControlGate(config).mode()).isEqualTo(RemoteControlGate.MODE_ASK);
    }

    @Test
    void approveThenRevoke_returnsToBlocked() {
        RemoteControlGate gate = gate("ask");

        assertThat(gate.approve()).isTrue();
        assertThat(gate.isInputAllowed()).isTrue();

        gate.revoke();
        assertThat(gate.isInputAllowed()).isFalse();
    }

    @Test
    void switchingToOff_dropsAnExistingApproval() {
        RemoteControlGate gate = gate("ask");
        gate.approve();

        gate.setMode(RemoteControlGate.MODE_OFF);

        assertThat(gate.isInputAllowed())
                .as("turning the feature off must not leave a live approval behind")
                .isFalse();
    }
}
