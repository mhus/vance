package de.mhus.vance.toolpack.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SmtpConfigTest {

    @Test
    void rejects_missing_host() {
        assertThatThrownBy(() -> SmtpConfig.fromParameters(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
    }

    @Test
    void defaults_to_starttls_on_587() {
        SmtpConfig cfg = SmtpConfig.fromParameters(Map.of("host", "smtp.example.com"));
        assertThat(cfg.port()).isEqualTo(587);
        assertThat(cfg.tls()).isFalse();
        assertThat(cfg.starttls()).isTrue();
        assertThat(cfg.protocol()).isEqualTo("smtp");
    }

    @Test
    void implicit_tls_picks_465_and_smtps() {
        SmtpConfig cfg = SmtpConfig.fromParameters(Map.of(
                "host", "smtp.example.com",
                "tls", true));
        assertThat(cfg.port()).isEqualTo(465);
        assertThat(cfg.tls()).isTrue();
        assertThat(cfg.starttls()).isFalse();  // tls=true defaults starttls=false
        assertThat(cfg.protocol()).isEqualTo("smtps");
    }

    @Test
    void plain_25_when_no_tls_and_no_starttls() {
        SmtpConfig cfg = SmtpConfig.fromParameters(Map.of(
                "host", "smtp.example.com",
                "tls", false,
                "starttls", false));
        assertThat(cfg.port()).isEqualTo(25);
        assertThat(cfg.protocol()).isEqualTo("smtp");
    }

    @Test
    void from_default_is_optional() {
        SmtpConfig cfg = SmtpConfig.fromParameters(Map.of("host", "smtp.example.com"));
        assertThat(cfg.from()).isEmpty();
    }

    @Test
    void allowlists_default_empty() {
        SmtpConfig cfg = SmtpConfig.fromParameters(Map.of("host", "smtp.example.com"));
        assertThat(cfg.allowedRecipientDomains()).isEmpty();
        assertThat(cfg.allowedFrom()).isEmpty();
    }

    @Test
    void parses_allowlists_from_comma_string_and_list() {
        SmtpConfig cfg = SmtpConfig.fromParameters(Map.of(
                "host", "smtp.example.com",
                "allowedRecipientDomains", "acme.com, ok.org",
                "allowedFrom", java.util.List.of("noreply@acme.com")));
        assertThat(cfg.allowedRecipientDomains()).containsExactly("acme.com", "ok.org");
        assertThat(cfg.allowedFrom()).containsExactly("noreply@acme.com");
    }
}
