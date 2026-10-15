package de.mhus.vance.brain.access;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Whether a login token (ACCESS, REFRESH) still belongs to a session the
 * account accepts — the account-side half of token validation, next to the
 * signature and expiry the JWT carries itself.
 *
 * <p>Two rules ({@code specification/public/sso-login.md} §6):
 * <ul>
 *   <li><b>Token floor</b> — a token issued at or before the account's
 *       {@link UserDocument#getTokensNotBefore() tokensNotBefore} is void.
 *       Preparing an account for SSO sets the floor, so no session started
 *       with the old password survives the switch: neither its ACCESS token at
 *       the filter nor its REFRESH token at the next re-mint.</li>
 *   <li><b>Capped renewal</b> — an account with an external identity renews
 *       only sessions that carry {@code sxp}. Every SSO login issues one; a
 *       token without it was not issued by an SSO login and must not start an
 *       open-ended refresh chain.</li>
 * </ul>
 *
 * <p>The filter asks on every request, so the account state it needs is
 * cached per account for a short window
 * ({@code vance.access.login-session-cache-ttl-seconds}, default 30 s) — the
 * same trade the OAuth grant liveness check makes. The re-mint endpoints load
 * the account anyway and use the uncached rules.
 */
@Service
@Slf4j
public class LoginSessionCheck {

    private static final int MAX_CACHED_ACCOUNTS = 10_000;

    private final UserService userService;
    private final Cache<AccountKey, Floor> floors;

    private record AccountKey(String tenantId, String username) {}

    /** What the filter needs of an account: its token floor, if any. */
    private record Floor(@Nullable Instant tokensNotBefore) {}

    public LoginSessionCheck(
            UserService userService,
            @Value("${vance.access.login-session-cache-ttl-seconds:30}") long cacheTtlSeconds) {
        this.userService = userService;
        this.floors = Caffeine.newBuilder()
                .maximumSize(MAX_CACHED_ACCOUNTS)
                .expireAfterWrite(Duration.ofSeconds(Math.max(0, cacheTtlSeconds)))
                .build();
    }

    /**
     * Filter check for an ACCESS token: is it above the account's token floor?
     * An account that does not exist has no floor — whether a token of a
     * missing account authenticates is not decided here.
     */
    public boolean admitsAccessToken(VanceJwtClaims claims) {
        Floor floor = floors.get(new AccountKey(claims.tenantId(), claims.username()), this::loadFloor);
        if (UserDocument.issuedAfterFloor(floor.tokensNotBefore(), claims.issuedAt())) {
            return true;
        }
        log.trace(
                "Login token rejected: issued {} at or before the account's token floor {} (tenant='{}' user='{}')",
                claims.issuedAt(),
                floor.tokensNotBefore(),
                claims.tenantId(),
                claims.username());
        return false;
    }

    /** Re-mint check (refresh-token login, {@code POST /refresh}) against the loaded account. */
    public static boolean mayRenew(UserDocument user, VanceJwtClaims claims) {
        if (!user.acceptsTokenIssuedAt(claims.issuedAt())) {
            return false;
        }
        // SSO accounts: only a session with a hard end may be extended.
        return user.getExternalIdentity() == null || claims.sessionExpiresAt() != null;
    }

    private Floor loadFloor(AccountKey key) {
        return new Floor(userService
                .findByTenantAndName(key.tenantId(), key.username())
                .map(UserDocument::getTokensNotBefore)
                .orElse(null));
    }
}
