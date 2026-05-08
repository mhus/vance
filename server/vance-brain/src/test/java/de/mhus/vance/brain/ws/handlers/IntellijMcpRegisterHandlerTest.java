package de.mhus.vance.brain.ws.handlers;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IntellijMcpRegisterHandlerTest {

    @Test
    void loopbackIp_accepted() {
        assertThat(IntellijMcpRegisterHandler.validateLoopback(
                "http://127.0.0.1:64342/stream")).isNull();
    }

    @Test
    void localhost_accepted() {
        assertThat(IntellijMcpRegisterHandler.validateLoopback(
                "http://localhost:64342/stream")).isNull();
    }

    @Test
    void ipv6Loopback_accepted() {
        assertThat(IntellijMcpRegisterHandler.validateLoopback(
                "http://[::1]:64342/stream")).isNull();
    }

    @Test
    void httpsLoopback_accepted() {
        assertThat(IntellijMcpRegisterHandler.validateLoopback(
                "https://127.0.0.1:64342/stream")).isNull();
    }

    @Test
    void remoteHost_rejected() {
        assertThat(IntellijMcpRegisterHandler.validateLoopback(
                "http://example.com/stream")).contains("loopback");
    }

    @Test
    void privateIp_rejected() {
        assertThat(IntellijMcpRegisterHandler.validateLoopback(
                "http://10.0.0.5:64342/stream")).contains("loopback");
    }

    @Test
    void fileScheme_rejected() {
        assertThat(IntellijMcpRegisterHandler.validateLoopback(
                "file:///etc/passwd")).contains("scheme");
    }

    @Test
    void garbage_rejected() {
        assertThat(IntellijMcpRegisterHandler.validateLoopback(
                "not a url at all"))
                .satisfiesAnyOf(
                        msg -> assertThat(msg).contains("URI"),
                        msg -> assertThat(msg).contains("scheme"));
    }
}
