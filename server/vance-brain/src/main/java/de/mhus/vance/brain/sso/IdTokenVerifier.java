package de.mhus.vance.brain.sso;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import java.security.Key;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Validates an OIDC ID token (OIDC Core §3.1.3.7) and returns the claims the
 * SSO login needs.
 *
 * <p>The token arrives directly from the token endpoint over TLS, which would
 * allow skipping the signature check — it is checked anyway: cheap, and it
 * still holds if the token ever reaches us another way.
 *
 * <p>Deliberately <em>not</em> checked here: {@code email_verified} and
 * {@code hd}. They are policy, and which rule applies (Workspace domain or
 * private Gmail) is decided by {@link SsoLoginService}.
 */
public final class IdTokenVerifier {

    /** Tolerated clock difference to the identity provider. */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private IdTokenVerifier() {}

    /** What the login needs from a valid ID token. */
    public record IdToken(
            String issuer,
            String subject,
            @Nullable String email,
            boolean emailVerified,
            @Nullable String hostedDomain,
            @Nullable String name) {}

    /** The token is not acceptable; the message is for the log, never for the user. */
    public static class InvalidIdTokenException extends Exception {
        public InvalidIdTokenException(String message) {
            super(message);
        }

        public InvalidIdTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * @param keys     public key for a {@code kid}, or {@code null} if unknown
     * @param issuers  accepted {@code iss} values
     * @param clientId our client id — {@code aud} (and {@code azp} when present)
     * @param nonce    the nonce of the pending sign-in
     */
    public static IdToken verify(
            String idToken,
            Function<String, @Nullable Key> keys,
            Set<String> issuers,
            String clientId,
            String nonce,
            Clock clock)
            throws InvalidIdTokenException {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .keyLocator(new LocatorAdapter<Key>() {
                        @Override
                        protected @Nullable Key locate(JwsHeader header) {
                            String kid = header.getKeyId();
                            return kid == null ? null : keys.apply(kid);
                        }
                    })
                    .clock(() -> Date.from(clock.instant()))
                    .clockSkewSeconds(CLOCK_SKEW.toSeconds())
                    .build()
                    .parseSignedClaims(idToken)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidIdTokenException("signature or structure rejected: " + e.getMessage(), e);
        }

        String issuer = claims.getIssuer();
        if (issuer == null || !issuers.contains(issuer)) {
            throw new InvalidIdTokenException("unexpected issuer '" + issuer + "'");
        }
        Set<String> audience = claims.getAudience();
        if (audience == null || !audience.contains(clientId)) {
            throw new InvalidIdTokenException("token was not issued for this client (aud)");
        }
        String azp = claims.get("azp", String.class);
        if ((audience.size() > 1 || azp != null) && !clientId.equals(azp)) {
            throw new InvalidIdTokenException("authorized party (azp) is not this client");
        }
        if (claims.getExpiration() == null) {
            throw new InvalidIdTokenException("token has no expiry");
        }
        Date iat = claims.getIssuedAt();
        Instant now = clock.instant();
        if (iat == null || iat.toInstant().isAfter(now.plus(CLOCK_SKEW))) {
            throw new InvalidIdTokenException("issued-at missing or in the future");
        }
        if (!nonce.equals(claims.get("nonce", String.class))) {
            throw new InvalidIdTokenException("nonce does not match the pending sign-in");
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new InvalidIdTokenException("token has no subject");
        }
        return new IdToken(
                issuer,
                subject,
                claims.get("email", String.class),
                isTrue(claims.get("email_verified")),
                claims.get("hd", String.class),
                claims.get("name", String.class));
    }

    /** Google sends a boolean; some providers send the string {@code "true"}. */
    private static boolean isTrue(@Nullable Object raw) {
        return Boolean.TRUE.equals(raw) || "true".equals(raw);
    }
}
