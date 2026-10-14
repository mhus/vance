package de.mhus.vance.brain.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.access.IntegrationTokenCreateRequest;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.integration.IntegrationTokenDocument;
import de.mhus.vance.shared.integration.IntegrationTokenService;
import de.mhus.vance.shared.jwt.JwtService;
import de.mhus.vance.shared.jwt.TokenType;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The mint's pin rules and the parent-grant link: a project-pinned profile
 * needs a {@code projectId}, a tenant-wide one refuses it, the two kinds do not
 * mix, and a token minted through an OAuth login remembers that grant.
 */
class IntegrationTokenControllerTest {

    private record Profile(String id, boolean requiresProject) implements IntegrationScopeProfile {
        @Override
        public String label() {
            return id;
        }

        @Override
        public List<IntegrationSurface> surfaces() {
            return List.of(IntegrationSurface.of("POST", "/" + id));
        }
    }

    private JwtService jwtService;
    private IntegrationTokenService tokenService;
    private RequestAuthority authority;
    private IntegrationTokenController controller;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        tokenService = mock(IntegrationTokenService.class);
        authority = mock(RequestAuthority.class);
        controller = new IntegrationTokenController(
                jwtService,
                tokenService,
                new IntegrationScopeRegistry(List.of(new Profile("links-capture", true), new Profile("mcp", false))),
                authority,
                mock(AuditService.class));
        when(tokenService.create(anyString(), anyString(), anyList(), any(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> IntegrationTokenDocument.builder()
                        .tokenId("tok-1")
                        .tenantId(inv.getArgument(0))
                        .userId(inv.getArgument(1))
                        .scopeProfiles(inv.getArgument(2))
                        .projectId(inv.getArgument(3))
                        .label(inv.getArgument(4))
                        .parentGrantId(inv.getArgument(7))
                        .build());
        when(jwtService.createIntegrationToken(anyString(), anyString(), anyString(), anyList(), any(), any()))
                .thenReturn("signed");
    }

    private static HttpServletRequest request(VanceJwtClaims claims) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(AccessFilterBase.ATTR_USERNAME)).thenReturn(claims.username());
        when(request.getAttribute(AccessFilterBase.ATTR_CLAIMS)).thenReturn(claims);
        return request;
    }

    private static VanceJwtClaims login() {
        return VanceJwtClaims.user("alice", "acme", null, null, TokenType.ACCESS);
    }

    private static IntegrationTokenCreateRequest mint(List<String> profiles, String projectId) {
        return IntegrationTokenCreateRequest.builder()
                .scopeProfiles(profiles)
                .projectId(projectId)
                .label("tool")
                .build();
    }

    private static HttpStatus statusOf(Runnable call) {
        try {
            call.run();
        } catch (ResponseStatusException e) {
            return HttpStatus.valueOf(e.getStatusCode().value());
        }
        return HttpStatus.OK;
    }

    @Test
    void tenantWideProfile_withProjectId_isRefused() {
        assertThat(statusOf(() -> controller.create("acme", mint(List.of("mcp"), "proj"), request(login()))))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(tokenService, never()).create(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void tenantWideProfile_withoutProjectId_isMinted() {
        assertThat(controller
                        .create("acme", mint(List.of("mcp"), null), request(login()))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
    }

    @Test
    void pinnedProfile_withoutProjectId_isRefused() {
        assertThat(statusOf(() -> controller.create("acme", mint(List.of("links-capture"), null), request(login()))))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void mixingPinnedAndTenantWideProfiles_isRefused() {
        assertThat(statusOf(() ->
                        controller.create("acme", mint(List.of("links-capture", "mcp"), "proj"), request(login()))))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statusOf(
                        () -> controller.create("acme", mint(List.of("links-capture", "mcp"), null), request(login()))))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void loginMint_hasNoParentGrant() {
        controller.create("acme", mint(List.of("mcp"), null), request(login()));

        verify(tokenService)
                .create(
                        eq("acme"),
                        eq("alice"),
                        eq(List.of("mcp")),
                        isNull(),
                        eq("tool"),
                        eq("alice"),
                        any(),
                        isNull());
    }

    @Test
    void oauthAccountMint_recordsTheGrantAsParent() {
        VanceJwtClaims oauthAccount =
                VanceJwtClaims.oauth("alice", "acme", null, null, "grant-7", List.of(), null, null, "client-1");

        controller.create("acme", mint(List.of("mcp"), null), request(oauthAccount));

        verify(tokenService)
                .create(
                        eq("acme"),
                        eq("alice"),
                        eq(List.of("mcp")),
                        isNull(),
                        eq("tool"),
                        eq("alice"),
                        any(),
                        eq("grant-7"));
    }

    @Test
    void narrowedCredential_cannotMint() {
        VanceJwtClaims narrowed =
                VanceJwtClaims.oauth("alice", "acme", null, null, "grant-7", List.of("mcp"), null, null, "client-1");

        assertThatThrownBy(() -> controller.create("acme", mint(List.of("mcp"), null), request(narrowed)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(
                                ((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(403));
    }
}
