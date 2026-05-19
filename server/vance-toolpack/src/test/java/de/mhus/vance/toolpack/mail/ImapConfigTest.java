package de.mhus.vance.toolpack.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ImapConfigTest {

    @Test
    void rejects_missing_host() {
        assertThatThrownBy(() -> ImapConfig.fromParameters(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
    }

    @Test
    void defaults_implicit_tls_on_993() {
        ImapConfig cfg = ImapConfig.fromParameters(Map.of("host", "imap.example.com"));
        assertThat(cfg.host()).isEqualTo("imap.example.com");
        assertThat(cfg.port()).isEqualTo(993);
        assertThat(cfg.tls()).isTrue();
        assertThat(cfg.starttls()).isFalse();
        assertThat(cfg.protocol()).isEqualTo("imaps");
        assertThat(cfg.defaultFolder()).isEqualTo("INBOX");
    }

    @Test
    void starttls_picks_143_default_and_imap_protocol() {
        ImapConfig cfg = ImapConfig.fromParameters(Map.of(
                "host", "imap.example.com",
                "tls", false,
                "starttls", true));
        assertThat(cfg.port()).isEqualTo(143);
        assertThat(cfg.protocol()).isEqualTo("imap");
    }

    @Test
    void explicit_port_overrides_default() {
        ImapConfig cfg = ImapConfig.fromParameters(Map.of(
                "host", "imap.example.com",
                "port", 1993));
        assertThat(cfg.port()).isEqualTo(1993);
    }

    @Test
    void defaultFolder_can_be_overridden() {
        ImapConfig cfg = ImapConfig.fromParameters(Map.of(
                "host", "imap.example.com",
                "defaultFolder", "Archive"));
        assertThat(cfg.defaultFolder()).isEqualTo("Archive");
    }
}
