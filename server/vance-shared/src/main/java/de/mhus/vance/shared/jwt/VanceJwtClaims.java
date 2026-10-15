package de.mhus.vance.shared.jwt;

import java.time.Instant;
import java.util.List;
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
 *
 * <p>The integration fields ({@link #scopeProfiles}, {@link #tokenId}) are
 * populated only for {@link TokenType#INTEGRATION}. {@code scopeProfiles} is a
 * <em>list</em> because one outside tool routinely does more than one thing —
 * a browser extension that both saves links and grabs pages needs two
 * capabilities but must be set up once. Keeping it a list rather than merging
 * the surfaces into one profile is what lets each module keep declaring its
 * own: a profile describes an integration's capability, not an app. {@link #projectId} is
 * shared with the script-run shape — both mean the same thing there, "this
 * token is pinned to that project", so a second claim for it would be two
 * spellings of one fact.
 *
 * <p>The design-preview field ({@link #appFolder}) is populated only for
 * {@link TokenType#DESIGN_PREVIEW} tokens and pins the credential to one
 * designer-app folder. {@code projectId} is shared with the script-run and
 * integration shapes — "pinned to that project" means the same thing in all
 * three.
 *
 * <p>The OAuth fields ({@link #audience}, {@link #clientId}) are populated
 * only for {@link TokenType#OAUTH} tokens. An OAuth token reuses the
 * integration fields with the same meaning — {@code jti} is the grant id (the
 * revocation key), {@code scp}/{@code pid} the narrowing; an empty {@code scp}
 * is the unrestricted {@code account} scope.
 */
public record VanceJwtClaims(
        String username,
        String tenantId,
        @Nullable Instant issuedAt,
        @Nullable Instant expiresAt,
        TokenType tokenType,
        @Nullable String runId,
        @Nullable String projectId,
        @Nullable String sessionId,
        List<String> scopeProfiles,
        @Nullable String tokenId,
        /** App-folder scope of a {@link TokenType#DESIGN_PREVIEW} token. */
        @Nullable String appFolder,
        /** Resource the token is bound to ({@code aud}) — {@link TokenType#OAUTH} only. */
        @Nullable String audience,
        /** OAuth client the token was issued to ({@code cid}) — {@link TokenType#OAUTH} only. */
        @Nullable String clientId,
        /**
         * Hard end of the login session this token belongs to ({@code sxp}) —
         * set only for sessions that started with an external sign-in (SSO).
         * Every re-mint (refresh token, {@code /refresh}) carries it forward
         * unchanged and refuses once it has passed; {@code null} means the
         * session has no such cap.
         */
        @Nullable Instant sessionExpiresAt) {

    public VanceJwtClaims {
        scopeProfiles = scopeProfiles == null ? List.of() : List.copyOf(scopeProfiles);
    }

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

    /** JWT claim name for the scope-profile ids of an
     *  {@link TokenType#INTEGRATION} token. Always a list on the wire. */
    public static final String CLAIM_SCOPE_PROFILES = "scp";

    /**
     * JWT claim name for the token id of an {@link TokenType#INTEGRATION}
     * token — the registry key that makes revocation possible. Standard
     * {@code jti} rather than a Vance-specific name: it is the registered
     * claim for exactly this, and nothing here needs it to mean more.
     */
    public static final String CLAIM_TOKEN_ID = "jti";

    /** JWT claim name for the app-folder scope of a
     *  {@link TokenType#DESIGN_PREVIEW} token.
     */
    public static final String CLAIM_APP_FOLDER = "fld";

    /** JWT claim name for the OAuth client id of a {@link TokenType#OAUTH} token. */
    public static final String CLAIM_CLIENT_ID = "cid";

    /**
     * JWT claim name for the session cap of an SSO login (epoch seconds) —
     * see {@link #sessionExpiresAt}.
     */
    public static final String CLAIM_SESSION_EXPIRES_AT = "sxp";

    /**
     * Whether this credential carries the account's full rights — an
     * {@link TokenType#ACCESS} token, or an {@link TokenType#OAUTH} token of
     * the unrestricted {@code account} scope (no {@code scp}).
     *
     * <p>The question every endpoint asks that mints or extends a credential:
     * a narrowed credential must not be able to produce credentials, or it
     * walks out of its own restriction one mint at a time.
     */
    public boolean isFullAccountCredential() {
        return tokenType == TokenType.ACCESS || (tokenType == TokenType.OAUTH && scopeProfiles.isEmpty());
    }

    /**
     * Standard user-token shape — no script-run scope fields. Matches
     * the historical 5-arg construction call-sites.
     */
    public static VanceJwtClaims user(
            String username,
            String tenantId,
            @Nullable Instant issuedAt,
            @Nullable Instant expiresAt,
            TokenType tokenType) {
        return new VanceJwtClaims(
                username, tenantId, issuedAt, expiresAt, tokenType, null, null, null, List.of(), null, null, null, null,
                null);
    }

    /**
     * Script-run shape — pins access to a single execution. Type is
     * always {@link TokenType#SCRIPT_RUN}.
     */
    public static VanceJwtClaims scriptRun(
            String username,
            String tenantId,
            @Nullable Instant issuedAt,
            @Nullable Instant expiresAt,
            String runId,
            String projectId,
            @Nullable String sessionId) {
        return new VanceJwtClaims(
                username,
                tenantId,
                issuedAt,
                expiresAt,
                TokenType.SCRIPT_RUN,
                runId,
                projectId,
                sessionId,
                List.of(),
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * Integration shape — a long-lived credential narrowed by a scope
     * profile and (optionally) pinned to one project. Type is always
     * {@link TokenType#INTEGRATION}.
     *
     * <p>{@code tokenId} is mandatory: it is the registry key, and a token
     * without one cannot be revoked. There is deliberately no overload that
     * omits it.
     */
    public static VanceJwtClaims integration(
            String username,
            String tenantId,
            @Nullable Instant issuedAt,
            @Nullable Instant expiresAt,
            String tokenId,
            List<String> scopeProfiles,
            @Nullable String projectId) {
        return new VanceJwtClaims(
                username,
                tenantId,
                issuedAt,
                expiresAt,
                TokenType.INTEGRATION,
                null,
                projectId,
                null,
                scopeProfiles,
                tokenId,
                null,
                null,
                null,
                null);
    }

    /**
     * Design-preview shape — pins read access to one designer-app folder.
     * Type is always {@link TokenType#DESIGN_PREVIEW}.
     */
    public static VanceJwtClaims designPreview(
            String username,
            String tenantId,
            @Nullable Instant issuedAt,
            @Nullable Instant expiresAt,
            String projectId,
            String appFolder) {
        return new VanceJwtClaims(
                username,
                tenantId,
                issuedAt,
                expiresAt,
                TokenType.DESIGN_PREVIEW,
                null,
                projectId,
                null,
                List.of(),
                null,
                appFolder,
                null,
                null,
                null);
    }

    /**
     * OAuth shape — an access token minted for one grant. Type is always
     * {@link TokenType#OAUTH}; {@code tokenId} is the grant id.
     */
    public static VanceJwtClaims oauth(
            String username,
            String tenantId,
            @Nullable Instant issuedAt,
            @Nullable Instant expiresAt,
            String grantId,
            List<String> scopeProfiles,
            @Nullable String projectId,
            @Nullable String audience,
            String clientId) {
        return new VanceJwtClaims(
                username,
                tenantId,
                issuedAt,
                expiresAt,
                TokenType.OAUTH,
                null,
                projectId,
                null,
                scopeProfiles,
                grantId,
                null,
                audience,
                clientId,
                null);
    }
}
