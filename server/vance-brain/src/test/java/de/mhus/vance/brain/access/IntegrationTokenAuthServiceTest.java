package de.mhus.vance.brain.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.integration.IntegrationTokenService;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntegrationTokenAuthServiceTest {

    private record CaptureProfile(boolean requiresProject) implements IntegrationScopeProfile {
        @Override
        public String id() {
            return "links-capture";
        }

        @Override
        public String label() {
            return "capture";
        }

        @Override
        public List<IntegrationSurface> surfaces() {
            return List.of(
                    IntegrationSurface.of("GET", "/addon/links/scan"),
                    IntegrationSurface.of("POST", "/addon/links/entry"));
        }
    }

    private IntegrationTokenService tokenService;
    private IntegrationTokenAuthService service;

    @BeforeEach
    void setUp() {
        tokenService = mock(IntegrationTokenService.class);
        when(tokenService.isActive(anyString(), anyString(), anyString())).thenReturn(true);
        service = new IntegrationTokenAuthService(tokenService,
                new IntegrationScopeRegistry(List.of(new CaptureProfile(true))));
    }

    private static VanceJwtClaims claims(String profile, String projectId, String jti) {
        return VanceJwtClaims.integration("alice", "acme", Instant.now(),
                Instant.now().plusSeconds(3600), jti, profile, projectId);
    }

    private static HttpServletRequest request(String method, String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(uri);
        return request;
    }

    @Test
    void accepts_aDeclaredSurface() {
        assertThat(service.isAcceptable(
                claims("links-capture", "links-proj", "tok-1"),
                request("POST", "/brain/acme/addon/links/entry"))).isTrue();
    }

    /**
     * The concrete thing the method check buys: {@code DELETE} shares the path
     * with {@code POST} on this route.
     */
    @Test
    void rejects_anUndeclaredMethodOnADeclaredPath() {
        assertThat(service.isAcceptable(
                claims("links-capture", "links-proj", "tok-1"),
                request("DELETE", "/brain/acme/addon/links/entry"))).isFalse();
    }

    @Test
    void rejects_anUndeclaredPath() {
        assertThat(service.isAcceptable(
                claims("links-capture", "links-proj", "tok-1"),
                request("GET", "/brain/acme/documents"))).isFalse();
    }

    /**
     * The profile is resolved against the running code. Removing one takes its
     * tokens with it rather than leaving them to mean whatever they meant when
     * they were minted.
     */
    @Test
    void rejects_anUnknownProfile() {
        assertThat(service.isAcceptable(
                claims("gone-away", "links-proj", "tok-1"),
                request("POST", "/brain/acme/addon/links/entry"))).isFalse();
    }

    /**
     * The path cannot bound the project — endpoints take it as a query
     * parameter — so a profile that needs one refuses a token without it.
     */
    @Test
    void rejects_aMissingProjectPin_whenTheProfileRequiresOne() {
        assertThat(service.isAcceptable(
                claims("links-capture", null, "tok-1"),
                request("POST", "/brain/acme/addon/links/entry"))).isFalse();
    }

    @Test
    void allowsAMissingProjectPin_whenTheProfileDoesNot() {
        IntegrationTokenAuthService unpinned = new IntegrationTokenAuthService(tokenService,
                new IntegrationScopeRegistry(List.of(new CaptureProfile(false))));

        assertThat(unpinned.isAcceptable(
                claims("links-capture", null, "tok-1"),
                request("POST", "/brain/acme/addon/links/entry"))).isTrue();
    }

    @Test
    void rejects_aMissingTokenId() {
        assertThat(service.isAcceptable(
                claims("links-capture", "links-proj", null),
                request("POST", "/brain/acme/addon/links/entry"))).isFalse();
    }

    @Test
    void rejects_aRevokedToken() {
        when(tokenService.isActive(anyString(), anyString(), anyString())).thenReturn(false);

        assertThat(service.isAcceptable(
                claims("links-capture", "links-proj", "tok-1"),
                request("POST", "/brain/acme/addon/links/entry"))).isFalse();
    }

    /**
     * The confinement does not survive the WS handshake — the post-upgrade
     * context is rebuilt from tenant + user alone. Refused here so a future
     * profile listing {@code /ws} fails loudly instead of quietly handing out
     * an unpinned socket.
     */
    @Test
    void rejects_theWebSocketUpgrade_evenWhenAProfileDeclaresIt() {
        IntegrationTokenAuthService wsProfile = new IntegrationTokenAuthService(tokenService,
                new IntegrationScopeRegistry(List.of(new WsProfile())));

        assertThat(wsProfile.isAcceptable(
                claims("ws-profile", "links-proj", "tok-1"),
                request("GET", "/brain/acme/ws"))).isFalse();
    }

    private record WsProfile() implements IntegrationScopeProfile {
        @Override
        public String id() {
            return "ws-profile";
        }

        @Override
        public String label() {
            return "ws";
        }

        @Override
        public List<IntegrationSurface> surfaces() {
            return List.of(IntegrationSurface.of("GET", "/ws"));
        }
    }

    @Test
    void rejects_aNonTenantPath() {
        assertThat(service.isAcceptable(
                claims("links-capture", "links-proj", "tok-1"),
                request("POST", "/internal/document/changed"))).isFalse();
    }

    /**
     * The database read happens only after the structural checks pass, so a
     * mismatched surface costs nothing.
     */
    @Test
    void doesNotHitTheRegistry_whenTheSurfaceDoesNotMatch() {
        service.isAcceptable(claims("links-capture", "links-proj", "tok-1"),
                request("DELETE", "/brain/acme/addon/links/entry"));

        verify(tokenService, never()).isActive(anyString(), anyString(), anyString());
    }
}
