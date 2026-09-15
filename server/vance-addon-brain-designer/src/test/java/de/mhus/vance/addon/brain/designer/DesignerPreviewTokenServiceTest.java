package de.mhus.vance.addon.brain.designer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.jwt.JwtService;
import de.mhus.vance.shared.keystore.KeyPurpose;
import de.mhus.vance.shared.keystore.KeyService;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DesignerPreviewTokenServiceTest {

    private KeyService keyService;
    private JwtService jwtService;
    private DesignerPreviewTokenService service;

    @BeforeEach
    void setUp() throws Exception {
        keyService = mock(KeyService.class);
        jwtService = new JwtService(keyService);

        KeyPair ec = ec();
        when(keyService.getLatestPrivateKey("acme", KeyPurpose.JWT_SIGNING)).thenReturn(Optional.of(ec.getPrivate()));
        when(keyService.getPublicKeys("acme", KeyPurpose.JWT_SIGNING)).thenReturn(List.of(ec.getPublic()));

        service = new DesignerPreviewTokenService(jwtService);
        ReflectionTestUtils.setField(
                service, "previewTokenTtlSeconds", DesignerPreviewTokenService.DEFAULT_TTL_SECONDS);
    }

    @Test
    void mintAndValidate_roundTripsScope() {
        DesignerPreviewSession session = service.mint("acme", "web", "designs", "alice");

        assertThat(session.getToken()).isNotBlank();
        assertThat(session.getExpiresAt()).isAfter(Instant.now());

        Optional<de.mhus.vance.shared.jwt.VanceJwtClaims> claims = service.validate(session.getToken(), "acme");

        assertThat(claims).isPresent();
        assertThat(claims.get().projectId()).isEqualTo("web");
        assertThat(claims.get().appFolder()).isEqualTo("designs");
        assertThat(claims.get().username()).isEqualTo("alice");
    }

    @Test
    void validate_rejectsForeignTenant() {
        String token = service.mint("acme", "web", "designs", "alice").getToken();

        assertThat(service.validate(token, "other")).isEmpty();
    }

    @Test
    void validate_rejectsAccessToken() {
        // A user ACCESS token must never authenticate the content route —
        // only DESIGN_PREVIEW does, and only this service validates it.
        String accessToken =
                jwtService.createToken("acme", "alice", Instant.now().plusSeconds(60));

        assertThat(service.validate(accessToken, "acme")).isEmpty();
    }

    @Test
    void validate_rejectsGarbage() {
        assertThat(service.validate("not-a-jwt", "acme")).isEmpty();
    }

    @Test
    void mint_normalisesFolderBeforeScoping() {
        // A folder written with stray slashes scopes the token to the
        // normalised form — the content route compares against the
        // manifest path, so both sides must agree on the spelling.
        DesignerPreviewSession session = service.mint("acme", "web", "/designs/", "alice");

        assertThat(service.validate(session.getToken(), "acme"))
                .map(c -> c.appFolder())
                .contains("designs");
    }

    private static KeyPair ec() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        return gen.generateKeyPair();
    }
}
