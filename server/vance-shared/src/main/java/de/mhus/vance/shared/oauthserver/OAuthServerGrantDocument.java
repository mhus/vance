package de.mhus.vance.shared.oauthserver;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One grant: a user said yes to one client for one resource. Every OAuth
 * access token carries its {@link #grantId} as {@code jti}, every refresh
 * token starts with it — so this row is the revocation channel for both, the
 * same role {@code integration_tokens} plays for integration tokens.
 *
 * <p>The narrowing lives here and in the token claims alike:
 * {@link #scopeProfiles} and {@link #projectId} are the profile and pin of
 * the door the grant was given for. An empty profile list is the
 * {@code account} scope — the full-account credential only first-party
 * clients may hold.
 *
 * <p>Refresh tokens rotate: {@link #refreshTokenHash} is the one live token
 * of the grant, {@link #previousRefreshTokenHashes} the last
 * {@code OAuthServerGrantService.REPLAY_HISTORY} it replaced, newest last. A
 * request presenting any of them is a replay — a copy of a token that was
 * already used — and revokes the whole grant (OAuth 2.1 §4.3.1). Keeping
 * several generations matters: an attacker who copied a token and refreshed
 * twice before the real client did would otherwise go unnoticed.
 *
 * <p>No TTL index: an expired or revoked grant stays visible in the owner's
 * list of connected apps, like an integration token row.
 */
@Document(collection = "oauth_server_grants")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthServerGrantDocument {

    @Id
    private @Nullable String id;

    /** {@code jti} of every access token minted for this grant. Unique. */
    @Indexed(unique = true)
    private String grantId = "";

    @Indexed
    private String tenantId = "";

    /** The account the tokens act as. Its permission grants remain the ceiling. */
    private String userId = "";

    @Indexed
    private String clientId = "";

    /** Client name as shown on the consent page — self-asserted, kept for the list. */
    private String clientName = "";

    /**
     * Canonical resource URL the grant is bound to (token {@code aud}), or
     * {@code null} for the {@code account} scope.
     */
    private @Nullable String resource;

    /** The OAuth scope string granted ({@code mcp}, {@code mcp-project}, {@code agents}, {@code account}). */
    private String scope = "";

    /** Integration scope profiles the tokens carry as {@code scp}; empty = unrestricted. */
    @Builder.Default
    private List<String> scopeProfiles = new ArrayList<>();

    /** Project pin ({@code pid}), or {@code null}. */
    private @Nullable String projectId;

    private @Nullable Instant createdAt;

    /** Last refresh or code redemption — what makes a forgotten grant visible. */
    private @Nullable Instant lastUsedAt;

    /** Set when revoked; any value here means every token of the grant is dead. */
    private @Nullable Instant revokedAt;

    /** Why it was revoked ({@code user}, {@code refresh-replay}, {@code code-replay}, …). */
    private @Nullable String revokeReason;

    /** SHA-256 of the live refresh token, {@code null} before the first code redemption. */
    @Indexed
    private @Nullable String refreshTokenHash;

    /**
     * SHA-256 of the refresh tokens the live one replaced, oldest first and
     * capped — presenting any of them is a replay.
     */
    @Builder.Default
    private List<String> previousRefreshTokenHashes = new ArrayList<>();

    /**
     * Legacy single-generation form of {@link #previousRefreshTokenHashes},
     * written by earlier versions. Still read for replay detection, cleared
     * when a new refresh strand starts; never written otherwise.
     */
    private @Nullable String previousRefreshTokenHash;

    /** Sliding expiry of the live refresh token. */
    private @Nullable Instant refreshExpiresAt;

    /** Hard end of the grant; no refresh reaches past it. */
    private @Nullable Instant absoluteExpiresAt;
}
