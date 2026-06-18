package de.mhus.vance.brain.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.ws.HandshakeHeaders;
import de.mhus.vance.brain.workspace.access.InternalAccessFilter;
import de.mhus.vance.shared.location.LocationService;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

class InternalChatHandshakeInterceptorTest {

    private static final String TOKEN = "test-secret";

    private LocationService locationService;
    private InternalChatHandshakeInterceptor interceptor;
    private ServerHttpResponse response;

    @BeforeEach
    void setUp() {
        locationService = mock(LocationService.class);
        when(locationService.getPodIp()).thenReturn("10.99.99.99");
        interceptor = new InternalChatHandshakeInterceptor(TOKEN, locationService);
        response = mock(ServerHttpResponse.class);
    }

    @Test
    void validHandshake_buildsForwardedConnectionContext() {
        ServerHttpRequest request = stub(
                "/internal/acme/ws/chat",
                Map.of(
                        InternalAccessFilter.HEADER_INTERNAL_TOKEN, TOKEN,
                        InternalChatHandshakeInterceptor.HDR_FORWARDED_TENANT_ID, "acme",
                        InternalChatHandshakeInterceptor.HDR_FORWARDED_USER_ID, "alice",
                        InternalChatHandshakeInterceptor.HDR_FORWARDED_DISPLAY_NAME, "Alice",
                        InternalChatHandshakeInterceptor.HDR_FORWARDED_CLIENT_IP, "10.0.0.7",
                        HandshakeHeaders.CLIENT_VERSION, "1.2.3",
                        HandshakeHeaders.PROFILE, "vance-foot",
                        HandshakeHeaders.CLIENT_NAME, "alice-laptop"));
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(
                request, response, mock(WebSocketHandler.class), attributes);

        assertThat(accepted).isTrue();
        ConnectionContext ctx =
                (ConnectionContext) attributes.get(VanceHandshakeInterceptor.ATTR_CONNECTION);
        assertThat(ctx).isNotNull();
        assertThat(ctx.getTenantId()).isEqualTo("acme");
        assertThat(ctx.getUserId()).isEqualTo("alice");
        assertThat(ctx.getDisplayName()).isEqualTo("Alice");
        assertThat(ctx.getProfile()).isEqualTo("vance-foot");
        assertThat(ctx.getClientVersion()).isEqualTo("1.2.3");
        assertThat(ctx.getClientName()).isEqualTo("alice-laptop");
        assertThat(ctx.getPodIp()).isEqualTo("10.0.0.7");
        assertThat(ctx.getConnectionId()).isNotBlank();
    }

    @Test
    void missingSharedSecret_rejectsWith401() {
        ServerHttpRequest request = stub(
                "/internal/acme/ws/chat",
                Map.of(
                        InternalChatHandshakeInterceptor.HDR_FORWARDED_TENANT_ID, "acme",
                        InternalChatHandshakeInterceptor.HDR_FORWARDED_USER_ID, "alice",
                        HandshakeHeaders.CLIENT_VERSION, "1.0"));
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(
                request, response, mock(WebSocketHandler.class), attributes);

        assertThat(accepted).isFalse();
        org.mockito.Mockito.verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
        assertThat(attributes).isEmpty();
    }

    @Test
    void wrongSharedSecret_rejectsWith401() {
        ServerHttpRequest request = stub(
                "/internal/acme/ws/chat",
                Map.of(
                        InternalAccessFilter.HEADER_INTERNAL_TOKEN, "wrong",
                        InternalChatHandshakeInterceptor.HDR_FORWARDED_TENANT_ID, "acme",
                        InternalChatHandshakeInterceptor.HDR_FORWARDED_USER_ID, "alice",
                        HandshakeHeaders.CLIENT_VERSION, "1.0"));

        boolean accepted = interceptor.beforeHandshake(
                request, response, mock(WebSocketHandler.class), new HashMap<>());

        assertThat(accepted).isFalse();
        org.mockito.Mockito.verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void missingForwardedIdentity_rejectsWith400() {
        ServerHttpRequest request = stub(
                "/internal/acme/ws/chat",
                Map.of(
                        InternalAccessFilter.HEADER_INTERNAL_TOKEN, TOKEN,
                        HandshakeHeaders.CLIENT_VERSION, "1.0"));

        boolean accepted = interceptor.beforeHandshake(
                request, response, mock(WebSocketHandler.class), new HashMap<>());

        assertThat(accepted).isFalse();
        org.mockito.Mockito.verify(response).setStatusCode(HttpStatus.BAD_REQUEST);
    }

    @Test
    void pathTenantMustMatchForwardedTenant_else403() {
        ServerHttpRequest request = stub(
                "/internal/acme/ws/chat",
                Map.of(
                        InternalAccessFilter.HEADER_INTERNAL_TOKEN, TOKEN,
                        InternalChatHandshakeInterceptor.HDR_FORWARDED_TENANT_ID, "different",
                        InternalChatHandshakeInterceptor.HDR_FORWARDED_USER_ID, "alice",
                        HandshakeHeaders.CLIENT_VERSION, "1.0"));

        boolean accepted = interceptor.beforeHandshake(
                request, response, mock(WebSocketHandler.class), new HashMap<>());

        assertThat(accepted).isFalse();
        org.mockito.Mockito.verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test
    void displayNameDefaultsToUserId() {
        ServerHttpRequest request = stub(
                "/internal/acme/ws/chat",
                Map.of(
                        InternalAccessFilter.HEADER_INTERNAL_TOKEN, TOKEN,
                        InternalChatHandshakeInterceptor.HDR_FORWARDED_TENANT_ID, "acme",
                        InternalChatHandshakeInterceptor.HDR_FORWARDED_USER_ID, "bob",
                        HandshakeHeaders.CLIENT_VERSION, "1.0"));
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(
                request, response, mock(WebSocketHandler.class), attributes);

        assertThat(accepted).isTrue();
        ConnectionContext ctx =
                (ConnectionContext) attributes.get(VanceHandshakeInterceptor.ATTR_CONNECTION);
        assertThat(ctx.getDisplayName()).isEqualTo("bob");
    }

    @Test
    void missingClientVersion_rejectsWith400() {
        ServerHttpRequest request = stub(
                "/internal/acme/ws/chat",
                Map.of(
                        InternalAccessFilter.HEADER_INTERNAL_TOKEN, TOKEN,
                        InternalChatHandshakeInterceptor.HDR_FORWARDED_TENANT_ID, "acme",
                        InternalChatHandshakeInterceptor.HDR_FORWARDED_USER_ID, "alice"));

        boolean accepted = interceptor.beforeHandshake(
                request, response, mock(WebSocketHandler.class), new HashMap<>());

        assertThat(accepted).isFalse();
        org.mockito.Mockito.verify(response).setStatusCode(HttpStatus.BAD_REQUEST);
    }

    @Test
    void missingForwardedClientIp_fallsBackToPodIp() {
        ServerHttpRequest request = stub(
                "/internal/acme/ws/chat",
                Map.of(
                        InternalAccessFilter.HEADER_INTERNAL_TOKEN, TOKEN,
                        InternalChatHandshakeInterceptor.HDR_FORWARDED_TENANT_ID, "acme",
                        InternalChatHandshakeInterceptor.HDR_FORWARDED_USER_ID, "alice",
                        HandshakeHeaders.CLIENT_VERSION, "1.0"));
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(
                request, response, mock(WebSocketHandler.class), attributes);

        assertThat(accepted).isTrue();
        ConnectionContext ctx =
                (ConnectionContext) attributes.get(VanceHandshakeInterceptor.ATTR_CONNECTION);
        assertThat(ctx.getPodIp()).isEqualTo("10.99.99.99");
    }

    private static ServerHttpRequest stub(String path, Map<String, String> headers) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("http://example.test" + path));
        HttpHeaders httpHeaders = new HttpHeaders();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            httpHeaders.add(e.getKey(), e.getValue());
        }
        when(request.getHeaders()).thenReturn(httpHeaders);
        return request;
    }
}
