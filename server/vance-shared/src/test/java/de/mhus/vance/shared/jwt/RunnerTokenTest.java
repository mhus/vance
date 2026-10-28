package de.mhus.vance.shared.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.runner.protocol.JtiCache;
import de.mhus.vance.runner.protocol.RunnerTokens;
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

/**
 * The runner-bound token as the brain really mints it ({@link JwtService}) held against
 * the runner's own verifier ({@link RunnerTokens}) — the two halves are written in
 * different modules and must agree.
 */
class RunnerTokenTest {

    private KeyPair tenantKey;
    private JwtService jwt;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        tenantKey = generator.generateKeyPair();
        KeyService keys = mock(KeyService.class);
        when(keys.getLatestPrivateKey("acme", KeyPurpose.JWT_SIGNING)).thenReturn(Optional.of(tenantKey.getPrivate()));
        when(keys.getPublicKeys("acme", KeyPurpose.JWT_SIGNING)).thenReturn(List.of(tenantKey.getPublic()));
        jwt = new JwtService(keys);
    }

    @Test
    void theRunnerVerifiesWhatTheBrainMints() {
        Instant now = Instant.now();
        String token = jwt.createRunnerToken(
                "acme", "alice", "runner-acme", "kunde-x", "_python", "call-1", now.plusSeconds(60));

        RunnerTokens.Claims claims =
                RunnerTokens.verify(token, List.of(tenantKey.getPublic()), "runner-acme", now, new JtiCache(10));

        assertThat(claims.tenantId()).isEqualTo("acme");
        assertThat(claims.projectId()).isEqualTo("kunde-x");
        assertThat(claims.rootDir()).isEqualTo("_python");
        assertThat(claims.jti()).isEqualTo("call-1");
    }

    @Test
    void withoutARootDir_noRootClaimIsWritten() {
        Instant now = Instant.now();
        String token =
                jwt.createRunnerToken("acme", "alice", "runner-acme", "kunde-x", null, "call-2", now.plusSeconds(60));

        assertThat(RunnerTokens.verify(token, List.of(tenantKey.getPublic()), "runner-acme", now, new JtiCache(10))
                        .rootDir())
                .isNull();
    }

    @Test
    void aRunnerToken_isNeverALoginToken() {
        String token = jwt.createRunnerToken(
                "acme",
                "alice",
                "runner-acme",
                "kunde-x",
                null,
                "call-3",
                Instant.now().plusSeconds(60));

        VanceJwtClaims claims = jwt.validateToken(token).orElseThrow();

        // The brain can read the token it minted, but as its own type — and no access
        // filter accepts that type (see BrainAccessFilterTest / AccessFilterBase).
        assertThat(claims.tokenType()).isEqualTo(TokenType.RUNNER);
        assertThat(claims.isFullAccountCredential()).isFalse();
    }

    @Test
    void theWireValue_isReadBackAsRunner_notAsTheAccessDefault() {
        // fromWire reads an unknown "tt" as ACCESS — the enum entry is what stops a
        // runner token from being a login token.
        assertThat(TokenType.fromWire("runner")).isEqualTo(TokenType.RUNNER);
        assertThat(TokenType.fromWire("some-future-type")).isEqualTo(TokenType.ACCESS);
        assertThat(TokenType.RUNNER.wireValue()).isEqualTo("runner");
    }
}
