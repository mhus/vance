package de.mhus.vance.brain.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.ws.HandshakeHeaders;
import de.mhus.vance.api.ws.Profiles;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.jwt.TokenType;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import de.mhus.vance.shared.location.LocationService;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.socket.WebSocketHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * Handshake parsing for the {@code clientContext} transport paths and the
 * canonical profile values. The Facelift desktop app connects through a
 * browser WebSocket that cannot set custom headers — its platform context
 * travels as the {@code ?clientContext=} query parameter instead
 * ({@code planning/desktop-agent-tools.md} §7), and its connection profile
 * is {@link Profiles#DESKTOP}.
 */
class VanceHandshakeInterceptorTest {

    private VanceHandshakeInterceptor interceptor;
    private ServerHttpResponse response;

    @BeforeEach
    void setUp() {
        LocationService locationService = mock(LocationService.class);
        when(locationService.getPodIp()).thenReturn("10.99.99.99");
        interceptor = new VanceHandshakeInterceptor(locationService, new ObjectMapper());
        response = mock(ServerHttpResponse.class);
    }

    @Test
    void clientContextAsQueryParam_isParsedIntoConnectionContext() {
        MockHttpServletRequest servlet = baseServlet("0.1.0");
        servlet.addParameter(
                HandshakeHeaders.CLIENT_CONTEXT_PARAM,
                "{\"os\":\"macos\",\"arch\":\"arm64\",\"shell\":\"/bin/zsh\","
                        + "\"cwd\":\"/Users/x\",\"sandboxEnabled\":true}");

        Map<String, Object> attributes = new HashMap<>();
        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(servlet), response, mock(WebSocketHandler.class), attributes);

        assertThat(accepted).isTrue();
        ConnectionContext ctx = (ConnectionContext) attributes.get(VanceHandshakeInterceptor.ATTR_CONNECTION);
        assertThat(ctx.getClientContext()).isNotNull();
        assertThat(ctx.getClientContext().getOs()).isEqualTo("macos");
        assertThat(ctx.getClientContext().getArch()).isEqualTo("arm64");
        assertThat(ctx.getClientContext().getShell()).isEqualTo("/bin/zsh");
        assertThat(ctx.getClientContext().getCwd()).isEqualTo("/Users/x");
        assertThat(ctx.getClientContext().isSandboxEnabled()).isTrue();
    }

    @Test
    void clientContextHeader_beatsQueryParam() {
        // Header carries macos, query param claims windows — header wins.
        MockHttpServletRequest servlet = baseServlet("1.0");
        servlet.addHeader(HandshakeHeaders.CLIENT_CONTEXT, "{\"os\":\"macos\",\"sandboxEnabled\":true}");
        servlet.addParameter(HandshakeHeaders.CLIENT_CONTEXT_PARAM, "{\"os\":\"windows\",\"sandboxEnabled\":true}");

        Map<String, Object> attributes = new HashMap<>();
        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(servlet), response, mock(WebSocketHandler.class), attributes);

        assertThat(accepted).isTrue();
        ConnectionContext ctx = (ConnectionContext) attributes.get(VanceHandshakeInterceptor.ATTR_CONNECTION);
        assertThat(ctx.getClientContext()).isNotNull();
        assertThat(ctx.getClientContext().getOs()).isEqualTo("macos");
    }

    @Test
    void malformedQueryParam_isIgnoredAndHandshakeSucceeds() {
        MockHttpServletRequest servlet = baseServlet("1.0");
        servlet.addParameter(HandshakeHeaders.CLIENT_CONTEXT_PARAM, "{not json");

        Map<String, Object> attributes = new HashMap<>();
        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(servlet), response, mock(WebSocketHandler.class), attributes);

        assertThat(accepted).isTrue();
        ConnectionContext ctx = (ConnectionContext) attributes.get(VanceHandshakeInterceptor.ATTR_CONNECTION);
        assertThat(ctx.getClientContext()).isNull();
    }

    @Test
    void desktopProfile_isAcceptedAndStored() {
        MockHttpServletRequest servlet = baseServlet("0.1.0");
        servlet.addParameter(HandshakeHeaders.PROFILE_PARAM, Profiles.DESKTOP);

        Map<String, Object> attributes = new HashMap<>();
        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(servlet), response, mock(WebSocketHandler.class), attributes);

        assertThat(accepted).isTrue();
        ConnectionContext ctx = (ConnectionContext) attributes.get(VanceHandshakeInterceptor.ATTR_CONNECTION);
        assertThat(ctx.getProfile()).isEqualTo(Profiles.DESKTOP);
    }

    private static MockHttpServletRequest baseServlet(String clientVersion) {
        MockHttpServletRequest servlet = new MockHttpServletRequest();
        servlet.setAttribute(AccessFilterBase.ATTR_CLAIMS, claims());
        servlet.addHeader(HandshakeHeaders.CLIENT_VERSION, clientVersion);
        return servlet;
    }

    private static VanceJwtClaims claims() {
        return new VanceJwtClaims("alice", "tnt-1", null, null, TokenType.ACCESS, null, null, null, null, null, null);
    }
}
