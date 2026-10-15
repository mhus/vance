package de.mhus.vance.brain.access;

import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.jwt.JwtService;
import de.mhus.vance.shared.jwt.TokenType;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Mints the credentials of a successful login — the one path shared by the
 * password login ({@link AccessController}) and the SSO login, so both end in
 * exactly the same session: home bootstrap, ACCESS token, optional REFRESH
 * token.
 *
 * <p>Authentication is the caller's job; this only issues.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoginTokenIssuer {

    /** Lifetime of an ACCESS token. */
    public static final Duration TOKEN_LIFETIME = Duration.ofHours(24);

    /**
     * Refresh-token lifetime. Long enough that a daily-active user
     * never re-types their password under normal circumstances, short
     * enough that a leaked refresh token decays on its own without
     * server-side revocation infrastructure.
     */
    public static final Duration REFRESH_TOKEN_LIFETIME = Duration.ofDays(30);

    private final JwtService jwtService;
    private final HomeBootstrapService homeBootstrapService;

    /** What a login hands out. Expiries are already capped by the session end. */
    public record Issued(
            String accessToken,
            Instant accessExpiresAt,
            @Nullable String refreshToken,
            @Nullable Instant refreshExpiresAt) {}

    /**
     * @param sessionExpiresAt hard end of the session ({@code sxp}) for an SSO
     *     login — carried by every token and capping its expiry; {@code null}
     *     for a password session
     */
    public Issued issue(String tenant, String username, boolean withRefreshToken, @Nullable Instant sessionExpiresAt) {
        // First-login Hub bootstrap: ensures the per-user vance-<login>
        // SYSTEM project (and the tenant-level Home group) exist before
        // the client opens a hub session. Idempotent — a no-op on
        // every subsequent login. Failure logs and re-throws; we'd
        // rather block the login than mint a token for a user whose
        // hub can't be opened.
        homeBootstrapService.ensureHome(tenant, username);
        // Tenant-wide _tenant system project — holds the override layer
        // for tenant-level documents/prompts/memory. Idempotent and cheap.
        homeBootstrapService.ensureTenantProject(tenant);

        Instant now = Instant.now();
        Instant expiresAt = cap(now.plus(TOKEN_LIFETIME), sessionExpiresAt);
        String token = jwtService.createToken(tenant, username, expiresAt, TokenType.ACCESS, sessionExpiresAt);
        if (!withRefreshToken) {
            log.info("Issued JWT tenant='{}' user='{}' expiresAt={}", tenant, username, expiresAt);
            return new Issued(token, expiresAt, null, null);
        }
        Instant refreshExpiresAt = cap(now.plus(REFRESH_TOKEN_LIFETIME), sessionExpiresAt);
        String refreshToken =
                jwtService.createToken(tenant, username, refreshExpiresAt, TokenType.REFRESH, sessionExpiresAt);
        log.info(
                "Issued JWT + refresh tenant='{}' user='{}' expiresAt={} refreshExpiresAt={} sessionEnd={}",
                tenant,
                username,
                expiresAt,
                refreshExpiresAt,
                sessionExpiresAt);
        return new Issued(token, expiresAt, refreshToken, refreshExpiresAt);
    }

    /** Re-mints an ACCESS token for a still-valid session ({@code POST /refresh}). */
    public Issued reissueAccess(String tenant, String username, @Nullable Instant sessionExpiresAt) {
        Instant expiresAt = cap(Instant.now().plus(TOKEN_LIFETIME), sessionExpiresAt);
        String token = jwtService.createToken(tenant, username, expiresAt, TokenType.ACCESS, sessionExpiresAt);
        return new Issued(token, expiresAt, null, null);
    }

    /** Whether a session with this end is over. {@code null} never ends here. */
    public static boolean sessionEnded(@Nullable Instant sessionExpiresAt) {
        return sessionExpiresAt != null && !Instant.now().isBefore(sessionExpiresAt);
    }

    private static Instant cap(Instant expiresAt, @Nullable Instant sessionExpiresAt) {
        return sessionExpiresAt != null && sessionExpiresAt.isBefore(expiresAt) ? sessionExpiresAt : expiresAt;
    }
}
