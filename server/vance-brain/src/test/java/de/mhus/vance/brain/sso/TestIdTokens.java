package de.mhus.vance.brain.sso;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import java.util.function.UnaryOperator;

/** Signs ID tokens the way Google does (RS256, {@code kid} header) for tests. */
final class TestIdTokens {

    static final String KID = "test-key-1";
    static final String CLIENT_ID = "client-1.apps.googleusercontent.com";
    static final String ISSUER = "https://accounts.google.com";

    final KeyPair keys;

    TestIdTokens() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            this.keys = gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A valid token for {@code email}; {@code tweak} adjusts it per test. */
    String token(String subject, String email, String nonce, UnaryOperator<JwtBuilder> tweak) {
        Instant now = Instant.now();
        JwtBuilder builder = Jwts.builder()
                .header()
                .keyId(KID)
                .and()
                .issuer(ISSUER)
                .audience()
                .add(CLIENT_ID)
                .and()
                .subject(subject)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .claim("azp", CLIENT_ID)
                .claim("nonce", nonce)
                .claim("email", email)
                .claim("email_verified", true);
        return tweak.apply(builder).signWith(keys.getPrivate()).compact();
    }
}
