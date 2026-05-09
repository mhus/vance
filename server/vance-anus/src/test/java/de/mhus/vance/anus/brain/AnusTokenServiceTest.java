package de.mhus.vance.anus.brain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.jwt.JwtService;
import de.mhus.vance.shared.jwt.TokenType;
import de.mhus.vance.shared.tenant.TenantDocument;
import de.mhus.vance.shared.tenant.TenantService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Wires up {@link AnusTokenService} with mocked collaborators and
 * verifies the contract: every mint ensures the target tenant + admin
 * user, then signs a token with the right subject, tenant and type.
 *
 * <p>End-to-end JWT roundtrip is exercised by {@code JwtServiceTest} in
 * {@code vance-shared} — here we only check Anus's wiring, not the
 * crypto.
 */
class AnusTokenServiceTest {

    private static final String TENANT = "acme";

    private TenantService tenantService;
    private UserService userService;
    private JwtService jwtService;
    private AnusBrainProperties properties;
    private AnusTokenService tokenService;

    @BeforeEach
    void setUp() {
        tenantService = mock(TenantService.class);
        userService = mock(UserService.class);
        jwtService = mock(JwtService.class);
        properties = new AnusBrainProperties();
        properties.setAdminTokenTtl(Duration.ofSeconds(45));

        when(tenantService.ensure(eq(TENANT), any()))
                .thenReturn(TenantDocument.builder().name(TENANT).build());
        when(userService.ensureVanceServiceAccount(eq(TENANT), eq("_vance-admin"),
                any(), any(), any()))
                .thenReturn(UserDocument.builder()
                        .tenantId(TENANT).name("_vance-admin")
                        .serviceAccount(true).loginEnabled(false).build());
        when(jwtService.createToken(eq(TENANT), eq("_vance-admin"),
                any(Instant.class), eq(TokenType.ACCESS)))
                .thenReturn("signed-jwt");

        tokenService = new AnusTokenService(tenantService, userService, jwtService, properties);
    }

    @Test
    void mintAdminToken_ensuresTenantAndAdminThenSignsToken() {
        String token = tokenService.mintAdminToken(TENANT);

        assertThat(token).isEqualTo("signed-jwt");
        verify(tenantService).ensure(eq(TENANT), any());
        verify(userService).ensureVanceServiceAccount(eq(TENANT), eq("_vance-admin"),
                any(), any(), any());
    }

    @Test
    void mintAdminToken_signsWithAccessTokenTypeAndConfiguredTtl() {
        Instant before = Instant.now();
        tokenService.mintAdminToken(TENANT);
        Instant after = Instant.now();

        ArgumentCaptor<Instant> expCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(jwtService).createToken(eq(TENANT), eq("_vance-admin"),
                expCaptor.capture(), eq(TokenType.ACCESS));

        // The mint sets expiresAt = now + ttl. Bound the captured value
        // by the real clock window we just observed.
        Instant exp = expCaptor.getValue();
        assertThat(exp).isAfterOrEqualTo(before.plus(properties.getAdminTokenTtl()).minusMillis(50));
        assertThat(exp).isBeforeOrEqualTo(after.plus(properties.getAdminTokenTtl()).plusMillis(50));
    }
}
