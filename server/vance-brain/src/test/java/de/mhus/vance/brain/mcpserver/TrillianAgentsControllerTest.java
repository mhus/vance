package de.mhus.vance.brain.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The HTTP edge of the agents door: tenant READ before anything is
 * dispatched, the caller taken from the filter chain, and GET answered with
 * the spec-conform 405 — there is no server-push stream.
 */
@ExtendWith(MockitoExtension.class)
class TrillianAgentsControllerTest {

    private static final String TENANT = "acme";
    private static final String FRAME = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";

    @Mock
    private TrillianAgentsService service;

    @Mock
    private RequestAuthority authority;

    @Mock
    private HttpServletRequest request;

    private TrillianAgentsController controller;

    @BeforeEach
    void setUp() {
        controller = new TrillianAgentsController(service, authority);
    }

    @Test
    void postEnforcesTenantRead_thenDispatchesAsTheAuthenticatedCaller() {
        when(request.getAttribute(AccessFilterBase.ATTR_USERNAME)).thenReturn("road.runner");
        when(service.handle(FRAME, TENANT, "road.runner"))
                .thenReturn(McpProtocol.Outcome.body(Map.of("jsonrpc", "2.0")));

        ResponseEntity<?> response = controller.rpc(TENANT, FRAME, request);

        verify(authority).enforce(request, new Resource.Tenant(TENANT), Action.READ);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aCallerWithoutTenantRead_neverReachesTheDispatch() {
        doThrow(mock(PermissionDeniedException.class))
                .when(authority)
                .enforce(request, new Resource.Tenant(TENANT), Action.READ);

        assertThatThrownBy(() -> controller.rpc(TENANT, FRAME, request)).isInstanceOf(PermissionDeniedException.class);
        verify(service, never()).handle(anyString(), anyString(), any());
    }

    @Test
    void aNotificationIsAcknowledgedWithoutBody() {
        when(service.handle(anyString(), anyString(), any())).thenReturn(McpProtocol.Outcome.ack());

        ResponseEntity<?> response = controller.rpc(TENANT, FRAME, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void getAnswers405_noServerPushStream() {
        assertThat(controller.noStream(TENANT).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }
}
