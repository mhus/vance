package de.mhus.vance.brain.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.JwtBuilder;
import java.security.Key;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class IdTokenVerifierTest {

    private static final String NONCE = "nonce-1";
    private final TestIdTokens tokens = new TestIdTokens();
    private final Function<String, @Nullable Key> keys =
            kid -> TestIdTokens.KID.equals(kid) ? tokens.keys.getPublic() : null;

    private IdTokenVerifier.IdToken verify(UnaryOperator<JwtBuilder> tweak) throws Exception {
        return IdTokenVerifier.verify(
                tokens.token("sub-1", "wile@acme.com", NONCE, tweak),
                keys,
                Set.of(TestIdTokens.ISSUER, "accounts.google.com"),
                TestIdTokens.CLIENT_ID,
                NONCE,
                Clock.systemUTC());
    }

    @Test
    void validToken_yieldsClaims() throws Exception {
        IdTokenVerifier.IdToken token = verify(b -> b.claim("hd", "acme.com").claim("name", "Wile"));

        assertThat(token.subject()).isEqualTo("sub-1");
        assertThat(token.email()).isEqualTo("wile@acme.com");
        assertThat(token.emailVerified()).isTrue();
        assertThat(token.hostedDomain()).isEqualTo("acme.com");
        assertThat(token.name()).isEqualTo("Wile");
    }

    @Test
    void foreignAudience_isRejected() {
        assertThatThrownBy(() -> verify(b -> b.audience().single("someone-else")))
                .isInstanceOf(IdTokenVerifier.InvalidIdTokenException.class);
    }

    @Test
    void foreignAuthorizedParty_isRejected() {
        assertThatThrownBy(() -> verify(b -> b.claim("azp", "someone-else")))
                .isInstanceOf(IdTokenVerifier.InvalidIdTokenException.class);
    }

    @Test
    void wrongNonce_isRejected() {
        assertThatThrownBy(() -> verify(b -> b.claim("nonce", "replayed")))
                .isInstanceOf(IdTokenVerifier.InvalidIdTokenException.class)
                .hasMessageContaining("nonce");
    }

    @Test
    void wrongIssuer_isRejected() {
        assertThatThrownBy(() -> verify(b -> b.issuer("https://evil.example")))
                .isInstanceOf(IdTokenVerifier.InvalidIdTokenException.class);
    }

    @Test
    void expiredToken_isRejected() {
        Instant past = Instant.now().minusSeconds(7200);
        assertThatThrownBy(() ->
                        verify(b -> b.issuedAt(Date.from(past.minusSeconds(60))).expiration(Date.from(past))))
                .isInstanceOf(IdTokenVerifier.InvalidIdTokenException.class);
    }

    @Test
    void issuedInTheFuture_isRejected() {
        assertThatThrownBy(() -> verify(b -> b.issuedAt(Date.from(Instant.now().plusSeconds(600)))))
                .isInstanceOf(IdTokenVerifier.InvalidIdTokenException.class);
    }

    @Test
    void unknownKey_isRejected() {
        assertThatThrownBy(() -> verify(b -> b.header().keyId("other-key").and()))
                .isInstanceOf(IdTokenVerifier.InvalidIdTokenException.class);
    }

    @Test
    void signatureByAnotherKey_isRejected() {
        TestIdTokens forger = new TestIdTokens();
        String forged = forger.token("sub-1", "wile@acme.com", NONCE, b -> b);
        assertThatThrownBy(() -> IdTokenVerifier.verify(
                        forged, keys, Set.of(TestIdTokens.ISSUER), TestIdTokens.CLIENT_ID, NONCE, Clock.systemUTC()))
                .isInstanceOf(IdTokenVerifier.InvalidIdTokenException.class);
    }

    @Test
    void emailVerified_acceptsStringTrue() throws Exception {
        assertThat(verify(b -> b.claim("email_verified", "true")).emailVerified())
                .isTrue();
        assertThat(verify(b -> b.claim("email_verified", false)).emailVerified())
                .isFalse();
    }
}
