package de.mhus.vance.addon.brain.rlang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.toolpack.ToolException;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the cold-path decision logic of {@link RserveDaemonManager}.
 *
 * <p>The actual process spawn (ProcessBuilder, stream pump, port poll) is
 * integration territory — exercised by the local Docker smoke test in
 * {@code readme/r-script-setup.md} and by hand. Here we cover the three
 * outcomes that must not trigger a spawn:
 *
 * <ol>
 *   <li>Daemon already answering → no-op.</li>
 *   <li>Autostart off and daemon unreachable → clean ToolException.</li>
 *   <li>Remote host configured → clean ToolException, no local spawn.</li>
 * </ol>
 */
class RserveDaemonManagerTest {

    @Test
    void ensureRunning_disabled_throws() {
        RserveProperties props = newProps(false, true, "127.0.0.1");
        RserveHealth health = mock(RserveHealth.class);

        RserveDaemonManager mgr = new RserveDaemonManager(props, health);

        assertThatThrownBy(mgr::ensureRunning)
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("disabled");
        verify(health, never()).isReachable();
    }

    @Test
    void ensureRunning_alreadyReachable_doesNothing() {
        RserveProperties props = newProps(true, true, "127.0.0.1");
        RserveHealth health = mock(RserveHealth.class);
        when(health.isReachable()).thenReturn(true);

        RserveDaemonManager mgr = new RserveDaemonManager(props, health);
        mgr.ensureRunning();

        // Hot path: single probe, no spawn attempt.
        verify(health, times(1)).isReachable();
    }

    @Test
    void ensureRunning_autostartDisabledAndUnreachable_throwsClean() {
        RserveProperties props = newProps(true, false, "127.0.0.1");
        RserveHealth health = mock(RserveHealth.class);
        when(health.isReachable()).thenReturn(false);

        RserveDaemonManager mgr = new RserveDaemonManager(props, health);

        assertThatThrownBy(mgr::ensureRunning)
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("autostart=false");
    }

    @Test
    void ensureRunning_remoteHostAndUnreachable_throwsWithoutSpawn() {
        RserveProperties props = newProps(true, true, "10.0.0.5");
        RserveHealth health = mock(RserveHealth.class);
        when(health.isReachable()).thenReturn(false);

        RserveDaemonManager mgr = new RserveDaemonManager(props, health);

        assertThatThrownBy(mgr::ensureRunning)
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not loopback");
    }

    @Test
    void ensureRunning_localhostHostMatchesAsLoopback() {
        // Sanity: 'localhost' must be treated as loopback so a typical
        // dev config (host: localhost) does not get the "remote" error.
        RserveProperties props = newProps(true, false, "localhost");
        RserveHealth health = mock(RserveHealth.class);
        when(health.isReachable()).thenReturn(false);

        RserveDaemonManager mgr = new RserveDaemonManager(props, health);

        // autostart=false here, so the failure should be about autostart,
        // not about the host. That proves "localhost" passed the loopback check.
        assertThatThrownBy(mgr::ensureRunning)
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("autostart=false");
        assertThat(props.getHost()).isEqualToIgnoringCase("localhost");
    }

    private static RserveProperties newProps(boolean enabled, boolean autostart, String host) {
        RserveProperties p = new RserveProperties();
        p.setEnabled(enabled);
        p.setAutostart(autostart);
        p.setHost(host);
        p.setPort(6311);
        p.setStartupTimeoutSec(2);
        return p;
    }
}
