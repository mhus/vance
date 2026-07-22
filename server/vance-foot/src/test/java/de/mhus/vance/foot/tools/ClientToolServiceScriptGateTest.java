package de.mhus.vance.foot.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the script-bridge sandbox bypass (code-review B5):
 * {@code client.tools.call(...)} must run through the same
 * {@link ClientSecurityService} gate as brain-driven dispatch, so a
 * denied {@code client_exec_run} cannot be executed from JavaScript.
 */
class ClientToolServiceScriptGateTest {

    private ClientSecurityService security;
    private AtomicBoolean toolRan;
    private ClientToolService service;

    @BeforeEach
    void setUp() {
        security = mock(ClientSecurityService.class);
        toolRan = new AtomicBoolean(false);

        ClientTool execTool = new ClientTool() {
            @Override public String name() { return "client_exec_run"; }
            @Override public String description() { return "run"; }
            @Override public boolean primary() { return true; }
            @Override public Map<String, Object> paramsSchema() { return Map.of(); }
            @Override public Map<String, Object> invoke(Map<String, Object> params) {
                toolRan.set(true);
                return Map.of("ran", true);
            }
        };

        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<de.mhus.vance.foot.connection.ConnectionService> conn =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<ClientToolPrettyRenderer> rend =
                mock(org.springframework.beans.factory.ObjectProvider.class);

        service = new ClientToolService(List.of(execTool), security, conn, rend);
    }

    @Test
    void invokeFromScript_deniedByPolicy_throwsAndDoesNotRun() {
        when(security.permit(eq("client_exec_run"), any())).thenReturn(false);
        when(security.denyReason(eq("client_exec_run"), any()))
                .thenReturn("denied by permissions.yaml");

        assertThatThrownBy(() -> service.invokeFromScript(
                "client_exec_run", Map.of("command", "rm -rf ~")))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("denied by permissions.yaml");

        assertThat(toolRan).isFalse();
    }

    @Test
    void invokeFromScript_allowedByPolicy_runs() {
        when(security.permit(eq("client_exec_run"), any())).thenReturn(true);

        Map<String, Object> result = service.invokeFromScript(
                "client_exec_run", Map.of("command", "ls"));

        assertThat(result).containsEntry("ran", true);
        assertThat(toolRan).isTrue();
    }

    @Test
    void invokeFromScript_unknownTool_throws() {
        assertThatThrownBy(() -> service.invokeFromScript("client_file_write", Map.of()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Unknown client tool");
    }

    @Test
    void invokeFromScript_suppressed_throws() {
        service.setSuppressed(true);

        assertThatThrownBy(() -> service.invokeFromScript("client_exec_run", Map.of()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("disabled");
        assertThat(toolRan).isFalse();
    }
}
