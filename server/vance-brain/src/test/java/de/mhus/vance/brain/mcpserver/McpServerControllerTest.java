package de.mhus.vance.brain.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.jwt.TokenType;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The global MCP door's own guards: a project-pinned credential is refused
 * outright (the pin would not survive into the tool dispatch), a narrowed one
 * needs an entry in {@code mcp-global-access.yaml}.
 */
class McpServerControllerTest {

    private static final String TENANT = "acme";
    private static final String FRAME = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";

    private McpServerService service;
    private RequestAuthority authority;
    private McpGlobalAccessService globalAccess;
    private McpServerController controller;

    @BeforeEach
    void setUp() {
        service = mock(McpServerService.class);
        authority = mock(RequestAuthority.class);
        globalAccess = mock(McpGlobalAccessService.class);
        controller = new McpServerController(service, authority, globalAccess);
        when(service.handle(anyString(), anyString(), anyString(), any()))
                .thenReturn(McpProtocol.Outcome.body(Map.of("jsonrpc", "2.0")));
    }

    private static HttpServletRequest request(VanceJwtClaims claims) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(AccessFilterBase.ATTR_CLAIMS)).thenReturn(claims);
        when(request.getAttribute(AccessFilterBase.ATTR_USERNAME)).thenReturn(claims.username());
        return request;
    }

    @Test
    void accountToken_isDispatched() {
        HttpServletRequest request = request(VanceJwtClaims.user("alice", TENANT, null, null, TokenType.ACCESS));

        ResponseEntity<?> response = controller.rpc(TENANT, null, FRAME, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(service).handle(FRAME, TENANT, HomeBootstrapService.TENANT_PROJECT_NAME, "alice");
    }

    @Test
    void pinnedIntegrationToken_isRefusedBeforeAnything() {
        HttpServletRequest request =
                request(VanceJwtClaims.integration("alice", TENANT, null, null, "tok-1", List.of("mcp"), "proj"));

        ResponseEntity<?> response = controller.rpc(TENANT, null, FRAME, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(authority, globalAccess);
        verify(service, never()).handle(anyString(), anyString(), anyString(), any());
    }

    @Test
    void pinnedScriptRunToken_isRefused() {
        HttpServletRequest request =
                request(VanceJwtClaims.scriptRun("alice", TENANT, null, null, "run-1", "proj", null));

        assertThat(controller.rpc(TENANT, "proj", FRAME, request).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(service, never()).handle(anyString(), anyString(), anyString(), any());
    }

    @Test
    void pinnedCredential_isRefusedOnGetToo() {
        HttpServletRequest request =
                request(VanceJwtClaims.integration("alice", TENANT, null, null, "tok-1", List.of("mcp"), "proj"));

        assertThat(controller.noStream(TENANT, request).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void get_withoutPin_answers405() {
        HttpServletRequest request = request(VanceJwtClaims.user("alice", TENANT, null, null, TokenType.ACCESS));

        assertThat(controller.noStream(TENANT, request).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void unpinnedNarrowedToken_withoutConfigEntry_isRefused() {
        HttpServletRequest request =
                request(VanceJwtClaims.integration("alice", TENANT, null, null, "tok-1", List.of("mcp"), null));
        when(globalAccess.resolveEntry(any(), any())).thenReturn(null);

        assertThat(controller.rpc(TENANT, null, FRAME, request).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(service, never()).handle(anyString(), anyString(), anyString(), any());
    }
}
