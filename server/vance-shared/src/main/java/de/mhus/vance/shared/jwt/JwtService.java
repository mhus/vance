package de.mhus.vance.shared.jwt;

import de.mhus.vance.shared.keystore.KeyPurpose;
import de.mhus.vance.shared.keystore.KeyService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Creates and verifies Vance JWTs.
 *
 * <p>Tokens are signed with the tenant's latest ECC private key (purpose
 * {@link KeyPurpose#JWT_SIGNING}). The tenant id goes into the {@code tid} claim
 * so the verifier can pick the right key set without an external hint.
 *
 * <p>Verification is done by first reading the unverified {@code tid} claim,
 * then trying every currently enabled public key for that tenant. This keeps
 * key rotation transparent — old and new keys coexist until the old one is
 * disabled.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JwtService {

    private final KeyService keyService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Creates a signed JWT for {@code username} under {@code tenantId}.
     *
     * <p>Convenience overload that mints an {@link TokenType#ACCESS} token —
     * the historical default. New code that mints refresh tokens must use
     * {@link #createToken(String, String, Instant, TokenType)}.
     *
     * @param tenantId  tenant business name (e.g. {@code "default"})
     * @param username  the human-readable user identifier that ends up as {@code sub}
     * @param expiresAt absolute expiration time, or {@code null} for a non-expiring token
     * @return the compact JWT string
     * @throws IllegalStateException if no signing key exists for the tenant
     */
    public String createToken(String tenantId, String username, Instant expiresAt) {
        return createToken(tenantId, username, expiresAt, TokenType.ACCESS);
    }

    /**
     * Creates a signed JWT for {@code username} under {@code tenantId} with
     * an explicit {@link TokenType}. Refresh tokens carry {@code tt=refresh}
     * so {@link de.mhus.vance.shared.access.AccessFilterBase} can reject
     * them on regular API requests.
     */
    public String createToken(String tenantId, String username, Instant expiresAt,
                              TokenType type) {
        PrivateKey privateKey = keyService.getLatestPrivateKey(tenantId, KeyPurpose.JWT_SIGNING)
                .orElseThrow(() -> new IllegalStateException(
                        "No JWT signing key for tenant '" + tenantId + "'"));
        if (!"EC".equalsIgnoreCase(privateKey.getAlgorithm())) {
            throw new IllegalStateException(
                    "JWT signing key for tenant '" + tenantId + "' is not EC — got "
                            + privateKey.getAlgorithm());
        }

        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(username)
                .claim(VanceJwtClaims.CLAIM_TENANT_ID, tenantId)
                .claim(VanceJwtClaims.CLAIM_TOKEN_TYPE, type.wireValue())
                .issuedAt(Date.from(now));
        if (expiresAt != null) {
            builder.expiration(Date.from(expiresAt));
        }
        return builder.signWith(privateKey).compact();
    }

    /**
     * Verifies a JWT and returns its Vance claims if the signature checks out
     * against any currently-enabled public key for the token's tenant and the
     * token is not expired. Returns {@link Optional#empty()} otherwise.
     */
    public Optional<VanceJwtClaims> validateToken(String token) {
        String tenantId = extractUnverifiedTenantId(token).orElse(null);
        if (tenantId == null) {
            log.debug("JWT rejected: no {} claim", VanceJwtClaims.CLAIM_TENANT_ID);
            return Optional.empty();
        }
        for (PublicKey publicKey : keyService.getPublicKeys(tenantId, KeyPurpose.JWT_SIGNING)) {
            Optional<Jws<Claims>> verified = tryVerify(token, publicKey);
            if (verified.isPresent()) {
                return Optional.of(toClaims(verified.get().getPayload()));
            }
        }
        log.debug("JWT rejected: no public key verified the signature for tenant '{}'", tenantId);
        return Optional.empty();
    }

    private Optional<Jws<Claims>> tryVerify(String token, PublicKey publicKey) {
        try {
            return Optional.of(Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(token));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Reads the {@code tid} claim straight from the token's payload segment
     * without verifying the signature. Used only to pick the key set for
     * verification — the full signature check follows.
     */
    private Optional<String> extractUnverifiedTenantId(String token) {
        int firstDot = token.indexOf('.');
        int secondDot = firstDot < 0 ? -1 : token.indexOf('.', firstDot + 1);
        if (firstDot < 0 || secondDot < 0) {
            return Optional.empty();
        }
        String payloadB64 = token.substring(firstDot + 1, secondDot);
        try {
            byte[] payload = Base64.getUrlDecoder().decode(payloadB64);
            JsonNode json = objectMapper.readTree(payload);
            JsonNode tid = json.get(VanceJwtClaims.CLAIM_TENANT_ID);
            return tid == null || tid.isNull() ? Optional.empty() : Optional.of(tid.asText());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private VanceJwtClaims toClaims(Claims claims) {
        String username = claims.getSubject();
        String tenantId = claims.get(VanceJwtClaims.CLAIM_TENANT_ID, String.class);
        String tt = claims.get(VanceJwtClaims.CLAIM_TOKEN_TYPE, String.class);
        Date iat = claims.getIssuedAt();
        Date exp = claims.getExpiration();
        return new VanceJwtClaims(
                username,
                tenantId,
                iat == null ? null : iat.toInstant(),
                exp == null ? null : exp.toInstant(),
                TokenType.fromWire(tt));
    }
}
