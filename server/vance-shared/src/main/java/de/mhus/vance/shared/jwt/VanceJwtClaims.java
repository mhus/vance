package de.mhus.vance.shared.jwt;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Vance-specific view on verified JWT claims.
 *
 * Only the fields we commit to in the wire contract are exposed; raw jjwt
 * {@code Claims} stay inside {@link JwtService}.
 *
 * <p>The script-run fields ({@link #runId}, {@link #projectId},
 * {@link #sessionId}) are populated only for {@link TokenType#SCRIPT_RUN}
 * tokens and stay {@code null} for {@link TokenType#ACCESS} /
 * {@link TokenType#REFRESH}.
 */
public record VanceJwtClaims(
        String username,
        String tenantId,
        @Nullable Instant issuedAt,
        @Nullable Instant expiresAt,
        TokenType tokenType,
        @Nullable String runId,
        @Nullable String projectId,
        @Nullable String sessionId) {

    /** JWT claim name for the Vance tenant id. */
    public static final String CLAIM_TENANT_ID = "tid";

    /** JWT claim name for the {@link TokenType} discriminator. */
    public static final String CLAIM_TOKEN_TYPE = "tt";

    /** JWT claim name for the script-run id (only on
     *  {@link TokenType#SCRIPT_RUN} tokens). */
    public static final String CLAIM_RUN_ID = "srid";

    /** JWT claim name for the project id scope of a
     *  {@link TokenType#SCRIPT_RUN} token. */
    public static final String CLAIM_PROJECT_ID = "pid";

    /** JWT claim name for the session id scope of a
     *  {@link TokenType#SCRIPT_RUN} token (optional). */
    public static final String CLAIM_SESSION_ID = "sid";

    /**
     * Standard user-token shape — no script-run scope fields. Matches
     * the historical 5-arg construction call-sites.
     */
    public static VanceJwtClaims user(
            String username, String tenantId,
            @Nullable Instant issuedAt, @Nullable Instant expiresAt,
            TokenType tokenType) {
        return new VanceJwtClaims(username, tenantId, issuedAt, expiresAt,
                tokenType, null, null, null);
    }

    /**
     * Script-run shape — pins access to a single execution. Type is
     * always {@link TokenType#SCRIPT_RUN}.
     */
    public static VanceJwtClaims scriptRun(
            String username, String tenantId,
            @Nullable Instant issuedAt, @Nullable Instant expiresAt,
            String runId, String projectId, @Nullable String sessionId) {
        return new VanceJwtClaims(username, tenantId, issuedAt, expiresAt,
                TokenType.SCRIPT_RUN, runId, projectId, sessionId);
    }
}
