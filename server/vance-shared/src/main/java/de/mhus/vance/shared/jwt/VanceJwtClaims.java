package de.mhus.vance.shared.jwt;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Vance-specific view on verified JWT claims.
 *
 * Only the fields we commit to in the wire contract are exposed; raw jjwt
 * {@code Claims} stay inside {@link JwtService}.
 */
public record VanceJwtClaims(
        String username,
        String tenantId,
        @Nullable Instant issuedAt,
        @Nullable Instant expiresAt,
        TokenType tokenType) {

    /** JWT claim name for the Vance tenant id. */
    public static final String CLAIM_TENANT_ID = "tid";

    /** JWT claim name for the {@link TokenType} discriminator. */
    public static final String CLAIM_TOKEN_TYPE = "tt";
}
