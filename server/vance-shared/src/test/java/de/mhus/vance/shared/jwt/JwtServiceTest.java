package de.mhus.vance.shared.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.keystore.KeyPurpose;
import de.mhus.vance.shared.keystore.KeyService;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private KeyService keyService;
    private JwtService jwt;

    private KeyPair tenantA;
    private KeyPair tenantA_rotated;
    private KeyPair tenantB;

    @BeforeEach
    void setUp() throws Exception {
        keyService = mock(KeyService.class);
        jwt = new JwtService(keyService);

        tenantA = ec();
        tenantA_rotated = ec();
        tenantB = ec();

        // Default wiring — single key for tenantA / tenantB.
        when(keyService.getLatestPrivateKey("acme", KeyPurpose.JWT_SIGNING))
                .thenReturn(Optional.of(tenantA.getPrivate()));
        when(keyService.getPublicKeys("acme", KeyPurpose.JWT_SIGNING))
                .thenReturn(List.of(tenantA.getPublic()));

        when(keyService.getLatestPrivateKey("other", KeyPurpose.JWT_SIGNING))
                .thenReturn(Optional.of(tenantB.getPrivate()));
        when(keyService.getPublicKeys("other", KeyPurpose.JWT_SIGNING))
                .thenReturn(List.of(tenantB.getPublic()));
    }

    @Test
    void createAndValidate_roundTripsClaims() {
        Instant exp = Instant.now().plus(1, ChronoUnit.HOURS);
        String token = jwt.createToken("acme", "alice", exp);

        Optional<VanceJwtClaims> claims = jwt.validateToken(token);

        assertThat(claims).isPresent();
        assertThat(claims.get().username()).isEqualTo("alice");
        assertThat(claims.get().tenantId()).isEqualTo("acme");
        assertThat(claims.get().issuedAt()).isNotNull();
        assertThat(claims.get().expiresAt())
                .isCloseTo(exp, within1Sec()); // jjwt rounds to second
    }

    @Test
    void createToken_throws_whenNoSigningKey() {
        when(keyService.getLatestPrivateKey("nobody", KeyPurpose.JWT_SIGNING))
                .thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jwt.createToken("nobody", "alice", Instant.now().plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nobody");
    }

    @Test
    void validate_rejectsExpiredToken() {
        // Issue a token that's already expired.
        String token = jwt.createToken("acme", "alice", Instant.now().minusSeconds(60));

        assertThat(jwt.validateToken(token)).isEmpty();
    }

    @Test
    void validate_rejectsToken_signedByDifferentTenant() {
        // Sign with tenantA's key but claim tenantId=other —
        // the keys for "other" won't match the signature.
        String token = Jwts.builder()
                .subject("alice")
                .claim(VanceJwtClaims.CLAIM_TENANT_ID, "other")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(tenantA.getPrivate())
                .compact();

        assertThat(jwt.validateToken(token)).isEmpty();
    }

    @Test
    void validate_rejectsToken_withoutTenantClaim() {
        // Token with no `tid` claim — JwtService can't pick a key set.
        String token = Jwts.builder()
                .subject("alice")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(tenantA.getPrivate())
                .compact();

        assertThat(jwt.validateToken(token)).isEmpty();
    }

    @Test
    void validate_acceptsToken_signedByAnyEnabledKey_inRotationSet() {
        // Tenant has both old and new public keys — token signed with old
        // private key must still verify (rotation transparency).
        when(keyService.getPublicKeys("acme", KeyPurpose.JWT_SIGNING))
                .thenReturn(List.of(tenantA_rotated.getPublic(), tenantA.getPublic()));

        String token = jwt.createToken("acme", "alice",
                Instant.now().plusSeconds(60));

        assertThat(jwt.validateToken(token)).isPresent();
    }

    @Test
    void validate_rejectsGarbageToken() {
        assertThat(jwt.validateToken("not-a-token")).isEmpty();
        assertThat(jwt.validateToken("aaa.bbb.ccc")).isEmpty();
        assertThat(jwt.validateToken("")).isEmpty();
    }

    @Test
    void createToken_withoutExpiry_producesNonExpiringToken() {
        String token = jwt.createToken("acme", "alice", null);

        Optional<VanceJwtClaims> claims = jwt.validateToken(token);
        assertThat(claims).isPresent();
        assertThat(claims.get().expiresAt()).isNull();
    }

    private static KeyPair ec() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        return gen.generateKeyPair();
    }

    private static org.assertj.core.data.TemporalUnitOffset within1Sec() {
        return new org.assertj.core.data.TemporalUnitWithinOffset(
                1, ChronoUnit.SECONDS);
    }
}
