package de.mhus.vance.brain.office;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.MacAlgorithm;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Sign + verify HMAC-JWT tokens for the ONLYOFFICE / Collabora
 * integration. The secret comes from the per-tenant
 * {@code office.jwtSecret} setting (resolved by
 * {@link OfficeSettings}) — a separate key family from Vance's
 * own user-auth JWTs (which are EC-signed via
 * {@code de.mhus.vance.shared.jwt.JwtService}).
 *
 * <p>The document server signs its callbacks with the same secret;
 * Vance verifies on the way in. For URLs we hand to the document
 * server (download / callback) we sign on the way out so the
 * document server can verify.
 *
 * <p>HMAC instead of public-key because that's what ONLYOFFICE
 * supports out of the box and the trust boundary is mutual — both
 * sides are operator-owned, so a shared secret is the natural fit.
 */
@Service
@Slf4j
public class OfficeJwtService {

    /** Soft cap on token lifetime — we issue short-lived tokens
     *  per download/callback round so a leaked URL goes stale
     *  quickly. The document server caches the doc server-side,
     *  so we don't need long-lived tokens. */
    private static final Duration DEFAULT_TOKEN_TTL = Duration.ofHours(2);

    /**
     * Sign a payload with the per-tenant secret. The {@code typ}
     * parameter ends up in the {@code typ} claim so we can tell
     * download- and callback-tokens apart on verify.
     *
     * @param claims  claims to include — at minimum {@code docId};
     *                {@code typ}, {@code iat}, {@code exp} are set
     *                here automatically
     */
    public String sign(OfficeSettings.Snapshot office,
                       String typ,
                       Map<String, Object> claims) {
        SecretKey key = hmacKey(office);
        MacAlgorithm alg = macAlgorithm(office.algorithm());
        Instant now = Instant.now();
        return Jwts.builder()
                .claims(claims)
                .claim("typ", typ)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(DEFAULT_TOKEN_TTL)))
                .signWith(key, alg)
                .compact();
    }

    /**
     * Verify a token signed with the per-tenant secret. Returns
     * the decoded claims when the signature and expiry check out,
     * {@code null} otherwise — the caller turns that into an HTTP
     * 401 / 403 as appropriate.
     */
    @Nullable
    public Claims verify(OfficeSettings.Snapshot office, String token) {
        if (token == null || token.isBlank()) return null;
        SecretKey key = hmacKey(office);
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException e) {
            log.warn("OfficeJwtService: token rejected — {}", e.getMessage());
            return null;
        }
    }

    /**
     * Build the HMAC key. The secret is treated as a raw bytestream
     * (UTF-8) — that matches ONLYOFFICE's convention and lets the
     * operator put either a hex string or a passphrase in the
     * setting without us having to guess.
     */
    static SecretKey hmacKey(OfficeSettings.Snapshot office) {
        String secret = office.jwtSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "office.jwtSecret is not configured for this scope");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        // jjwt rejects keys < 256 bits for HS256 (etc.) — guide
        // the operator with a clear message rather than letting
        // jjwt's "key length is XX bits" surface unexplained.
        int minBits = switch (office.algorithm() == null ? "HS256"
                : office.algorithm().toUpperCase(Locale.ROOT)) {
            case "HS512" -> 512;
            case "HS384" -> 384;
            default -> 256;
        };
        if (bytes.length * 8 < minBits) {
            throw new IllegalStateException(
                    "office.jwtSecret must be at least "
                            + (minBits / 8) + " bytes long for "
                            + office.algorithm() + " (was "
                            + bytes.length + " bytes)");
        }
        return Keys.hmacShaKeyFor(bytes);
    }

    /** Map the configured algorithm string to jjwt's MAC enum. */
    static MacAlgorithm macAlgorithm(@Nullable String algorithm) {
        if (algorithm == null || algorithm.isBlank()) {
            return Jwts.SIG.HS256;
        }
        return switch (algorithm.trim().toUpperCase(Locale.ROOT)) {
            case "HS256" -> Jwts.SIG.HS256;
            case "HS384" -> Jwts.SIG.HS384;
            case "HS512" -> Jwts.SIG.HS512;
            default -> throw new IllegalArgumentException(
                    "Unsupported office.jwtAlgorithm '" + algorithm
                            + "' — use HS256, HS384, or HS512");
        };
    }
}
