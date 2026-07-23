package de.mhus.vance.foot.connection;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.WindowTitleService;
import org.junit.jupiter.api.Test;

/**
 * Guards the plaintext-transport gate (code-review Phase 2): a non-loopback
 * brain over http/ws is refused unless allowInsecureTransport is set, while
 * loopback (local dev) and TLS always connect.
 */
class ConnectionServiceTransportTest {

    private ConnectionService service(String wsBase, boolean allowInsecure) {
        FootConfig config = new FootConfig();
        config.getBrain().setWsBase(wsBase);
        config.getBrain().setHttpBase(wsBase.replaceFirst("^ws", "http"));
        config.getBrain().setAllowInsecureTransport(allowInsecure);
        return new ConnectionService(
                config,
                mock(MessageDispatcher.class),
                mock(ChatTerminal.class),
                mock(SessionService.class),
                mock(WindowTitleService.class));
    }

    @Test
    void nonLoopbackPlaintext_isRefused() {
        assertThatThrownBy(() -> service("ws://10.0.0.5:8080", false).assertTransportAllowed())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("plaintext");
    }

    @Test
    void nonLoopbackPlaintext_allowedWhenFlagSet() {
        assertThatCode(() -> service("ws://10.0.0.5:8080", true).assertTransportAllowed())
                .doesNotThrowAnyException();
    }

    @Test
    void loopbackPlaintext_alwaysAllowed() {
        assertThatCode(() -> service("ws://localhost:8080", false).assertTransportAllowed())
                .doesNotThrowAnyException();
        assertThatCode(() -> service("ws://127.0.0.1:9000", false).assertTransportAllowed())
                .doesNotThrowAnyException();
    }

    @Test
    void tlsRemote_alwaysAllowed() {
        assertThatCode(() -> service("wss://brain.example.com", false).assertTransportAllowed())
                .doesNotThrowAnyException();
    }
}
