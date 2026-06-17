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

    @Test
    void readonly_defaults_true_and_trash_defaults_to_Trash() {
        ImapConfig cfg = ImapConfig.fromParameters(Map.of("host", "imap.example.com"));
        assertThat(cfg.readonly()).isTrue();
        assertThat(cfg.trashFolder()).isEqualTo("Trash");
    }

    @Test
    void readonly_can_be_disabled_and_trash_can_be_overridden() {
        ImapConfig cfg = ImapConfig.fromParameters(Map.of(
                "host", "imap.example.com",
                "readonly", false,
                "trashFolder", "[Gmail]/Trash"));
        assertThat(cfg.readonly()).isFalse();
        assertThat(cfg.trashFolder()).isEqualTo("[Gmail]/Trash");
    }

    @Test
    void readonly_accepts_string_false() {
        ImapConfig cfg = ImapConfig.fromParameters(Map.of(
                "host", "imap.example.com",
                "readonly", "false"));
        assertThat(cfg.readonly()).isFalse();
    }

    @Test
    void bodyMaxBytes_defaults_to_64k() {
        ImapConfig cfg = ImapConfig.fromParameters(Map.of("host", "imap.example.com"));
        assertThat(cfg.bodyMaxBytes()).isEqualTo(65536);
    }

    @Test
    void bodyMaxBytes_zero_means_unlimited() {
        ImapConfig cfg = ImapConfig.fromParameters(Map.of(
                "host", "imap.example.com",
                "bodyMaxBytes", 0));
        assertThat(cfg.bodyMaxBytes()).isEqualTo(0);
    }

    @Test
    void bodyMaxBytes_negative_clamps_to_zero() {
        ImapConfig cfg = ImapConfig.fromParameters(Map.of(
                "host", "imap.example.com",
                "bodyMaxBytes", -1));
        assertThat(cfg.bodyMaxBytes()).isEqualTo(0);
    }
}
