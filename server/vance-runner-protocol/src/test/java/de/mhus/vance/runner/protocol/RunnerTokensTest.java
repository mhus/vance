package de.mhus.vance.runner.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.runner.protocol.RunnerProtocolException.Kind;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The runner's own verifier against tokens minted exactly like the brain's
 * {@code JwtService} does it (jjwt, ES256, tenant key pair).
 */
class RunnerTokensTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    private static final String RUNNER = "runner-acme";

    private KeyPair tenantKey;
    private JtiCache replay;

    @BeforeEach
    void setUp() throws Exception {
        tenantKey = ecKeyPair();
        replay = new JtiCache(100);
    }

    private static KeyPair ecKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    /** A token as the brain would mint it; every claim can be overridden through the builder. */
    private io.jsonwebtoken.JwtBuilder minted(String jti) {
        return Jwts.builder()
                .subject("svc")
                .claim(RunnerTokenClaims.CLAIM_TENANT_ID, "acme")
                .claim(RunnerTokenClaims.CLAIM_TYPE, RunnerTokenClaims.TYPE)
                .claim(RunnerTokenClaims.CLAIM_PROJECT_ID, "kunde-x")
                .id(jti)
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(60)));
    }

    private String token(String jti) {
        io.jsonwebtoken.JwtBuilder builder = minted(jti);
        builder.audience().add(RunnerTokenClaims.audience(RUNNER));
        return builder.signWith(tenantKey.getPrivate()).compact();
    }

    private RunnerTokens.Claims verify(String token) {
        return RunnerTokens.verify(token, List.of(tenantKey.getPublic()), RUNNER, NOW, replay);
    }

    private static void assertRefused(Runnable action, String reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(RunnerProtocolException.class, e -> {
            assertThat(e.kind()).isEqualTo(Kind.TOKEN);
            assertThat(e.getMessage()).contains(reason);
        });
    }

    @Test
    void aBrainMintedToken_verifies_andYieldsItsClaims() {
        RunnerTokens.Claims claims = verify(token("j-1"));

        assertThat(claims.tenantId()).isEqualTo("acme");
        assertThat(claims.projectId()).isEqualTo("kunde-x");
        assertThat(claims.jti()).isEqualTo("j-1");
        assertThat(claims.rootDir()).isNull();
        assertThat(claims.expiresAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void theRootDirClaim_isCarried() {
        io.jsonwebtoken.JwtBuilder builder = minted("j-root").claim(RunnerTokenClaims.CLAIM_ROOT, "_python");
        builder.audience().add(RunnerTokenClaims.audience(RUNNER));

        assertThat(verify(builder.signWith(tenantKey.getPrivate()).compact()).rootDir())
                .isEqualTo("_python");
    }

    @Test
    void audience_asArrayOrString_andOnlyThisRunner() {
        io.jsonwebtoken.JwtBuilder several = minted("j-many");
        several.audience().add("runner:somebody-else").add(RunnerTokenClaims.audience(RUNNER));
        assertThat(verify(several.signWith(tenantKey.getPrivate()).compact()).jti())
                .isEqualTo("j-many");

        io.jsonwebtoken.JwtBuilder other = minted("j-other");
        other.audience().add(RunnerTokenClaims.audience("runner-beta"));
        String forOther = other.signWith(tenantKey.getPrivate()).compact();
        assertRefused(() -> verify(forOther), "not addressed to this runner");

        String noAudience = minted("j-none").signWith(tenantKey.getPrivate()).compact();
        assertRefused(() -> verify(noAudience), "not addressed to this runner");
    }

    @Test
    void aLoginToken_isNotARunnerToken() {
        io.jsonwebtoken.JwtBuilder builder = minted("j-login").claim(RunnerTokenClaims.CLAIM_TYPE, "access");
        builder.audience().add(RunnerTokenClaims.audience(RUNNER));
        String login = builder.signWith(tenantKey.getPrivate()).compact();

        assertRefused(() -> verify(login), "not a runner token");
    }

    @Test
    void expiredAndOverlongTokens_areRefused() {
        io.jsonwebtoken.JwtBuilder expired = minted("j-old").expiration(Date.from(NOW.minusSeconds(60)));
        expired.audience().add(RunnerTokenClaims.audience(RUNNER));
        assertRefused(() -> verify(expired.signWith(tenantKey.getPrivate()).compact()), "expired");

        io.jsonwebtoken.JwtBuilder forever = minted("j-forever").expiration(Date.from(NOW.plusSeconds(3600)));
        forever.audience().add(RunnerTokenClaims.audience(RUNNER));
        assertRefused(() -> verify(forever.signWith(tenantKey.getPrivate()).compact()), "lives longer");
    }

    @Test
    void aSmallClockSkew_isTolerated() {
        io.jsonwebtoken.JwtBuilder justExpired = minted("j-skew").expiration(Date.from(NOW.minusSeconds(2)));
        justExpired.audience().add(RunnerTokenClaims.audience(RUNNER));

        assertThat(verify(justExpired.signWith(tenantKey.getPrivate()).compact())
                        .jti())
                .isEqualTo("j-skew");
    }

    @Test
    void aTokenSignedByAnotherKey_isRefused_andRotationKeysAreAllTried() throws Exception {
        KeyPair stranger = ecKeyPair();
        String forged = token("j-forged").replaceAll("\\.[^.]+$", ".")
                + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[64]);
        assertRefused(() -> verify(forged), "signature does not verify");

        String signedByStranger;
        io.jsonwebtoken.JwtBuilder builder = minted("j-stranger");
        builder.audience().add(RunnerTokenClaims.audience(RUNNER));
        signedByStranger = builder.signWith(stranger.getPrivate()).compact();
        assertRefused(() -> verify(signedByStranger), "signature does not verify");

        // During a rotation the tenant has two enabled keys; either may have signed.
        List<PublicKey> both = List.of(stranger.getPublic(), tenantKey.getPublic());
        assertThat(RunnerTokens.verify(token("j-rot"), both, RUNNER, NOW, replay)
                        .jti())
                .isEqualTo("j-rot");
    }

    @Test
    void aNonEcKey_isIgnored_neverVerifiesAnything() throws Exception {
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        List<PublicKey> keys = List.of(rsa.generateKeyPair().getPublic());

        assertRefused(
                () -> RunnerTokens.verify(token("j-rsa"), keys, RUNNER, NOW, replay), "signature does not verify");
    }

    @Test
    void aTamperedPayload_failsTheSignature() {
        String[] parts = token("j-tamper").split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"kunde-x\"", "\"kunde-y\"");
        String tampered = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "."
                + parts[2];

        assertRefused(() -> verify(tampered), "signature does not verify");
    }

    @Test
    void otherAlgorithms_includingNone_areRefused() {
        String none = b64("{\"alg\":\"none\"}") + "." + b64("{\"tt\":\"runner\"}") + ".";
        String hmac = b64("{\"alg\":\"HS256\"}") + "." + b64("{\"tt\":\"runner\"}") + "." + b64("x".repeat(32));

        assertRefused(() -> verify(none), "not a compact JWS");
        assertRefused(() -> verify(hmac), "algorithm is not ES256");
        assertRefused(() -> verify("garbage"), "not a compact JWS");
        assertRefused(() -> verify("a.b.c"), "not base64url");
        assertRefused(() -> verify(b64("nope") + "." + b64("nope") + "." + b64("x")), "not JSON");
    }

    @Test
    void aTokenWorksOnlyOnce() {
        String token = token("j-once");

        verify(token);

        assertRefused(() -> verify(token), "already used");
    }

    @Test
    void aForgedTokenNeverOccupiesAReplaySlot() throws Exception {
        io.jsonwebtoken.JwtBuilder builder = minted("j-spam");
        builder.audience().add(RunnerTokenClaims.audience(RUNNER));
        String signedByStranger = builder.signWith(ecKeyPair().getPrivate()).compact();

        assertRefused(() -> verify(signedByStranger), "signature does not verify");

        assertThat(replay.size()).isZero();
    }

    @Test
    void theUnverifiedJti_isReadableEvenFromARefusedToken_andNeverFromJunk() throws Exception {
        io.jsonwebtoken.JwtBuilder builder = minted("j-readable");
        String wrongAudience = builder.signWith(tenantKey.getPrivate()).compact();

        assertRefused(() -> verify(wrongAudience), "not addressed to this runner");
        assertThat(RunnerTokens.unverifiedJti(wrongAudience)).contains("j-readable");
        assertThat(RunnerTokens.unverifiedJti("garbage")).isEmpty();
        assertThat(RunnerTokens.unverifiedJti("a.b.c")).isEmpty();
        assertThat(RunnerTokens.unverifiedJti(b64("{}") + "." + b64("{\"x\":1}") + ".sig"))
                .isEmpty();
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
