package de.mhus.vance.toolpack.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RestApiConfigTest {

    @Test
    void minimalParameters_pickUpDefaults() {
        RestApiConfig cfg = RestApiConfig.fromParameters(Map.of(
                "specUrl", "https://api.example.com/openapi.json"));

        assertThat(cfg.specUrl()).isEqualTo("https://api.example.com/openapi.json");
        assertThat(cfg.specInline()).isNull();
        assertThat(cfg.baseUrl()).isNull();
        assertThat(cfg.auth().type()).isEqualTo(AuthSpec.Type.NONE);
        assertThat(cfg.tls().skipVerification()).isFalse();
        assertThat(cfg.tls().trustedCaPem()).isNull();
        assertThat(cfg.timeoutSeconds()).isEqualTo(RestApiConfig.DEFAULT_TIMEOUT_SECONDS);
        assertThat(cfg.labelsForMethod("GET")).containsExactly("read-only");
        assertThat(cfg.labelsForMethod("POST")).contains("write", "side-effect");
    }

    @Test
    void neitherSpecUrlNorInline_throws() {
        assertThatThrownBy(() -> RestApiConfig.fromParameters(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("specUrl");
    }

    @Test
    void bearerAuth_carriesTokenReference() {
        RestApiConfig cfg = RestApiConfig.fromParameters(Map.of(
                "specInline", "openapi: 3.0.0\ninfo:\n  title: t\n  version: '1'\npaths: {}\n",
                "auth", Map.of(
                        "type", "bearer",
                        "token", "{{secret:my.api.token}}")));

        assertThat(cfg.auth().type()).isEqualTo(AuthSpec.Type.BEARER);
        assertThat(cfg.auth().token()).isEqualTo("{{secret:my.api.token}}");
    }

    @Test
    void tls_skipVerification_isPropagated() {
        RestApiConfig cfg = RestApiConfig.fromParameters(Map.of(
                "specUrl", "https://api.example.com/openapi.json",
                "tls", Map.of("skipVerification", true)));

        assertThat(cfg.tls().skipVerification()).isTrue();
    }

    @Test
    void tls_trustedCaPemPath_isPropagated() {
        RestApiConfig cfg = RestApiConfig.fromParameters(Map.of(
                "specUrl", "https://api.example.com/openapi.json",
                "tls", Map.of("trustedCaPemPath", "/etc/vance/example-ca.pem")));

        assertThat(cfg.tls().skipVerification()).isFalse();
        assertThat(cfg.tls().trustedCaPem()).isEqualTo("/etc/vance/example-ca.pem");
    }

    @Test
    void methodLabels_overrideDefault() {
        RestApiConfig cfg = RestApiConfig.fromParameters(Map.of(
                "specUrl", "https://api.example.com/openapi.json",
                "methodLabels", Map.of(
                        "GET", List.of("read-only", "audit"),
                        "POST", List.of("write"))));

        assertThat(cfg.labelsForMethod("GET")).containsExactly("read-only", "audit");
        assertThat(cfg.labelsForMethod("POST")).containsExactly("write");
        // Methods not overridden retain default mapping.
        assertThat(cfg.labelsForMethod("PUT")).contains("write", "side-effect");
    }

    @Test
    void unknownAuthType_throws() {
        assertThatThrownBy(() -> RestApiConfig.fromParameters(Map.of(
                "specUrl", "https://x",
                "auth", Map.of("type", "oauth"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("oauth");
    }
}
