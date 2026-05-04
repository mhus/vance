package de.mhus.vance.shared.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.jwt.JwtService;
import de.mhus.vance.shared.jwt.TokenType;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the security-critical claim-type gate in
 * {@link AccessFilterBase}. The chief property: a {@link TokenType#REFRESH}
 * token presented as {@code Authorization: Bearer ...} must NOT
 * authenticate any API request — it is a credential for the token-mint
 * endpoint only. Without this check a refresh-token leak would grant
 * full session access for 30 days.
 */
class AccessFilterBaseTest {

    private JwtService jwtService;
    private TestFilter filter;

    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;
    private StringWriter responseBody;

    @BeforeEach
    void setUp() throws Exception {
        jwtService = mock(JwtService.class);
        filter = new TestFilter(jwtService);

        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);

        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/brain/acme/sessions");

        responseBody = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseBody));
    }

    @Test
    void allowsAccessTokenInBearerHeader() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer access-jwt");
        when(jwtService.validateToken("access-jwt"))
                .thenReturn(Optional.of(claims("acme", "alice", TokenType.ACCESS)));

        filter.doFilterInternal(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(response, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(request).setAttribute(AccessFilterBase.ATTR_AUTHENTICATED, Boolean.TRUE);
        verify(request).setAttribute(AccessFilterBase.ATTR_USERNAME, "alice");
    }

    @Test
    void rejectsRefreshTokenInBearerHeader() throws Exception {
        // The whole point of the type-discriminator. A leaked refresh
        // token must not double as an API key.
        when(request.getHeader("Authorization")).thenReturn("Bearer refresh-jwt");
        when(jwtService.validateToken("refresh-jwt"))
                .thenReturn(Optional.of(claims("acme", "alice", TokenType.REFRESH)));

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(request, response);
        // Authenticated attribute must be FALSE so downstream code
        // (which won't run anyway, but defensively) can't treat the
        // request as logged-in.
        verify(request).setAttribute(AccessFilterBase.ATTR_AUTHENTICATED, Boolean.FALSE);
    }

    @Test
    void rejectsInvalidToken() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer garbage");
        when(jwtService.validateToken("garbage")).thenReturn(Optional.empty());

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void rejects_whenNoAuthHeaderOnProtectedRoute() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void allowsOptionsPreflight_withoutAuth() throws Exception {
        // CORS preflight has no Authorization header by spec — must
        // never be 401'd. The filter short-circuits before the token
        // check.
        when(request.getMethod()).thenReturn("OPTIONS");

        filter.doFilterInternal(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(response, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void allowsAccessTokenViaCookie_whenNoBearerHeader() throws Exception {
        // Web-UI bootstrap path: SPA holds nothing, browser sends the
        // {@code vance_access} cookie automatically. Filter must accept
        // it as a Bearer-equivalent and authenticate the request.
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getCookies()).thenReturn(new Cookie[]{
                new Cookie(WebUiCookies.ACCESS, "access-jwt"),
        });
        when(jwtService.validateToken("access-jwt"))
                .thenReturn(Optional.of(claims("acme", "alice", TokenType.ACCESS)));

        filter.doFilterInternal(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(request).setAttribute(AccessFilterBase.ATTR_AUTHENTICATED, Boolean.TRUE);
        verify(request).setAttribute(AccessFilterBase.ATTR_USERNAME, "alice");
    }

    @Test
    void rejectsRefreshTokenInAccessCookie() throws Exception {
        // Even via the cookie path the type-discriminator must hold —
        // a malicious page that smuggled a refresh token into the
        // {@code vance_access} cookie would otherwise gain full API
        // access for 30 days.
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getCookies()).thenReturn(new Cookie[]{
                new Cookie(WebUiCookies.ACCESS, "refresh-jwt"),
        });
        when(jwtService.validateToken("refresh-jwt"))
                .thenReturn(Optional.of(claims("acme", "alice", TokenType.REFRESH)));

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void bearerHeaderWins_overCookie_whenBothPresent() throws Exception {
        // If both the header and cookie are present, the explicit
        // header wins — the same precedence as before the cookie path
        // existed. Lets a CLI override a stale browser cookie at a
        // dual-purpose endpoint.
        when(request.getHeader("Authorization")).thenReturn("Bearer header-jwt");
        when(request.getCookies()).thenReturn(new Cookie[]{
                new Cookie(WebUiCookies.ACCESS, "cookie-jwt"),
        });
        when(jwtService.validateToken("header-jwt"))
                .thenReturn(Optional.of(claims("acme", "header-user", TokenType.ACCESS)));

        filter.doFilterInternal(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(request).setAttribute(AccessFilterBase.ATTR_USERNAME, "header-user");
        // Cookie was never consulted.
        verify(jwtService, never()).validateToken("cookie-jwt");
    }

    @Test
    void rejectsRefreshTokenViaQueryParam_onAllowedRoute() throws Exception {
        // ?token= fallback exists for routes that can't carry an auth
        // header (img/iframe). It must apply the same type-gate as the
        // header path — otherwise an exfiltrated refresh token in a
        // URL would grant access.
        filter.allowQueryToken = true;
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getParameter("token")).thenReturn("refresh-jwt");
        when(jwtService.validateToken("refresh-jwt"))
                .thenReturn(Optional.of(claims("acme", "alice", TokenType.REFRESH)));

        filter.doFilterInternal(request, response, chain);

        assertThat(responseBody.toString()).contains("401");
        verify(chain, never()).doFilter(request, response);
    }

    private static VanceJwtClaims claims(String tenantId, String username, TokenType type) {
        return new VanceJwtClaims(username, tenantId,
                Instant.now().minusSeconds(60),
                Instant.now().plusSeconds(86_400),
                type);
    }

    /**
     * Concrete test subclass — every protected route requires auth, no
     * subclass-specific claim cross-checks. {@code allowQueryToken}
     * toggles the {@code ?token=} fallback for the one test that
     * exercises that path.
     */
    private static final class TestFilter extends AccessFilterBase {
        boolean allowQueryToken = false;

        TestFilter(JwtService jwtService) {
            super(jwtService);
        }

        @Override
        protected boolean shouldRequireAuthentication(String requestUri, String method) {
            return true;
        }

        @Override
        protected boolean allowsQueryToken(String requestUri, String method) {
            return allowQueryToken;
        }
    }
}
