package de.mhus.vance.brain.access;

import de.mhus.vance.api.access.AccessTokenRequest;
import de.mhus.vance.api.access.AccessTokenResponse;
import de.mhus.vance.api.access.RefreshTokenResponse;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.access.WebUiCookies;
import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.jwt.JwtService;
import de.mhus.vance.shared.jwt.TokenType;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import de.mhus.vance.shared.password.PasswordService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint that mints JWTs after verifying username + password.
 *
 * <p>{@code POST /brain/{tenant}/access/{username}} with body
 * {@code {"password": "..."}} — returns a fresh token on success.
 *
 * <p>The path is exempted from {@link BrainAccessFilter} — callers have no
 * token yet when hitting this endpoint.
 *
 * <p>Auth rules:
 * <ul>
 *   <li>User must exist in {@code tenant}</li>
 *   <li>{@link UserDocument#getStatus()} must be {@link UserStatus#ACTIVE}</li>
 *   <li>User must have a {@code passwordHash} set (OAuth-only users can't
 *       log in here)</li>
 *   <li>{@code password} from the body must verify against the stored hash</li>
 * </ul>
 * Any failure returns {@code 401 Unauthorized} with no body — the specific
 * reason is only logged at DEBUG to avoid user-enumeration via response
 * differences.
 */
@RestController
@Slf4j
public class AccessController {

    private static final Duration TOKEN_LIFETIME = Duration.ofHours(24);

    /**
     * Refresh-token lifetime. Long enough that a daily-active user
     * never re-types their password under normal circumstances, short
     * enough that a leaked refresh token decays on its own without
     * server-side revocation infrastructure.
     */
    private static final Duration REFRESH_TOKEN_LIFETIME = Duration.ofDays(30);

    private final JwtService jwtService;
    private final UserService userService;
    private final PasswordService passwordService;
    private final HomeBootstrapService homeBootstrapService;
    private final AuditService auditService;
    private final WebUiCookieService webUiCookieService;

    public AccessController(JwtService jwtService,
                            UserService userService,
                            PasswordService passwordService,
                            HomeBootstrapService homeBootstrapService,
                            AuditService auditService,
                            WebUiCookieService webUiCookieService) {
        this.jwtService = jwtService;
        this.userService = userService;
        this.passwordService = passwordService;
        this.homeBootstrapService = homeBootstrapService;
        this.auditService = auditService;
        this.webUiCookieService = webUiCookieService;
    }

    @PostMapping("/brain/{tenant}/access/{username}")
    public ResponseEntity<AccessTokenResponse> createToken(
            @PathVariable("tenant") String tenant,
            @PathVariable("username") String username,
            @Valid @RequestBody AccessTokenRequest request,
            HttpServletRequest httpRequest) {

        boolean hasPassword = StringUtils.isNotBlank(request.getPassword());
        boolean hasRefreshToken = StringUtils.isNotBlank(request.getRefreshToken());

        // If no body credential is set, fall back to the refresh
        // cookie. This is the silent-login path the SPA takes when its
        // access cookie has expired but the refresh cookie is still
        // alive — the browser sends the {@code vance_refresh} cookie
        // automatically and the server uses it as the credential.
        if (!hasPassword && !hasRefreshToken) {
            String cookieRefreshToken = extractRefreshCookie(httpRequest);
            if (cookieRefreshToken != null) {
                request.setRefreshToken(cookieRefreshToken);
                hasRefreshToken = true;
            }
        }

        if (hasPassword == hasRefreshToken) {
            // 0 or 2 credentials — neither is a valid login attempt.
            // Same generic 401 as a bad password to avoid revealing
            // which field-shape the server expects.
            log.debug("Login rejected: must supply exactly one of password/refreshToken (tenant='{}' name='{}')",
                    tenant, username);
            return rejectLogin(tenant, username, "missing-credentials");
        }

        Optional<UserDocument> userOpt = userService.findByTenantAndName(tenant, username);
        if (userOpt.isEmpty()) {
            log.debug("Login rejected: unknown user tenant='{}' name='{}'", tenant, username);
            // Spend the same BCrypt cost as a real wrong-password check so
            // login latency can't enumerate valid usernames.
            if (hasPassword) passwordService.verifyDecoy(request.getPassword());
            return rejectLogin(tenant, username, "unknown-user");
        }
        UserDocument user = userOpt.get();

        if (user.getStatus() != UserStatus.ACTIVE) {
            log.debug("Login rejected: status={} tenant='{}' name='{}'", user.getStatus(), tenant, username);
            if (hasPassword) passwordService.verifyDecoy(request.getPassword());
            return rejectLogin(tenant, username, "user-not-active");
        }

        // Service accounts and temporarily login-blocked users (e.g. lockouts)
        // are rejected at this gate. Existing tokens stay valid until they
        // expire — the JWT filter does NOT consult loginEnabled, only the
        // mint endpoints do.
        if (!user.isLoginEnabled()) {
            log.debug("Login rejected: loginEnabled=false tenant='{}' name='{}' serviceAccount={}",
                    tenant, username, user.isServiceAccount());
            if (hasPassword) passwordService.verifyDecoy(request.getPassword());
            return rejectLogin(tenant, username, "login-disabled");
        }

        if (hasPassword) {
            String hash = user.getPasswordHash();
            if (hash == null) {
                log.debug("Login rejected: no password hash tenant='{}' name='{}'", tenant, username);
                passwordService.verifyDecoy(request.getPassword());
                return rejectLogin(tenant, username, "no-password-hash");
            }
            if (!passwordService.verify(request.getPassword(), hash)) {
                log.debug("Login rejected: bad password tenant='{}' name='{}'", tenant, username);
                return rejectLogin(tenant, username, "bad-password");
            }
        } else {
            // Refresh-token login: validate signature, kind, and that
            // the token's identity matches the URL path. Cross-user
            // reuse (tenant-A token presented at tenant-B URL, or
            // user-X token at user-Y URL) is rejected — every login
            // must agree on who is logging in.
            VanceJwtClaims claims = jwtService.validateToken(request.getRefreshToken()).orElse(null);
            if (claims == null) {
                log.debug("Login rejected: refresh token invalid (tenant='{}' name='{}')", tenant, username);
                return rejectLogin(tenant, username, "refresh-token-invalid");
            }
            if (claims.tokenType() != TokenType.REFRESH) {
                log.debug("Login rejected: presented {} token in refreshToken slot (tenant='{}' name='{}')",
                        claims.tokenType(), tenant, username);
                return rejectLogin(tenant, username, "refresh-token-wrong-type");
            }
            if (!tenant.equals(claims.tenantId()) || !username.equals(claims.username())) {
                log.debug("Login rejected: refresh token identity mismatch — path tenant='{}' user='{}', token tenant='{}' user='{}'",
                        tenant, username, claims.tenantId(), claims.username());
                return rejectLogin(tenant, username, "refresh-token-identity-mismatch");
            }
        }

        // First-login Hub bootstrap: ensures the per-user vance-<login>
        // SYSTEM project (and the tenant-level Home group) exist before
        // the client opens a hub session. Idempotent — a no-op on
        // every subsequent login. Failure logs and re-throws; we'd
        // rather block the login than mint a token for a user whose
        // hub can't be opened.
        homeBootstrapService.ensureHome(tenant, username);
        // Tenant-wide _vance system project — holds the override layer
        // for tenant-level documents/prompts/memory (resource lookup
        // logic lands in a follow-up). Idempotent and cheap.
        homeBootstrapService.ensureTenantProject(tenant);
        // Bundled server-tool defaults are served by DocumentService's
        // classpath resource layer (vance-defaults/server-tools/*.yaml);
        // no explicit seeding step is needed.

        Instant expiresAt = Instant.now().plus(TOKEN_LIFETIME);
        String token = jwtService.createToken(tenant, username, expiresAt, TokenType.ACCESS);

        AccessTokenResponse.AccessTokenResponseBuilder responseBuilder = AccessTokenResponse.builder()
                .token(token)
                .expiresAtTimestamp(expiresAt.toEpochMilli());

        String refreshToken = null;
        Instant refreshExpiresAt = null;
        if (request.isRequestRefreshToken()) {
            refreshExpiresAt = Instant.now().plus(REFRESH_TOKEN_LIFETIME);
            refreshToken = jwtService.createToken(tenant, username, refreshExpiresAt, TokenType.REFRESH);
            responseBuilder
                    .refreshToken(refreshToken)
                    .refreshTokenExpiresAtTimestamp(refreshExpiresAt.toEpochMilli());
            log.info("Issued JWT + refresh tenant='{}' user='{}' expiresAt={} refreshExpiresAt={}",
                    tenant, username, expiresAt, refreshExpiresAt);
        } else {
            log.info("Issued JWT tenant='{}' user='{}' expiresAt={}", tenant, username, expiresAt);
        }
        auditService.authLoginSuccess(tenant, username);

        ResponseEntity.BodyBuilder responseEntityBuilder = ResponseEntity.ok();
        if (request.isRequestCookies()) {
            webUiCookieService.attachLoginCookies(
                    responseEntityBuilder, user,
                    token, expiresAt,
                    refreshToken, refreshExpiresAt,
                    request.isIncludeWebUiSettings());
        }
        return responseEntityBuilder.body(responseBuilder.build());
    }

    /**
     * Re-mint a JWT in exchange for a still-valid one.
     *
     * <p>{@code POST /brain/{tenant}/refresh} — caller authenticates with their
     * current bearer token (validated by {@link BrainAccessFilter}). On success
     * a fresh token with a new {@link #TOKEN_LIFETIME} is issued for the same
     * user/tenant.
     *
     * <p>Re-checks that the user is still {@link UserStatus#ACTIVE} — a token
     * issued before the user was deactivated must not be refreshable.
     */
    @PostMapping("/brain/{tenant}/refresh")
    public ResponseEntity<RefreshTokenResponse> refreshToken(
            @PathVariable("tenant") String tenant,
            HttpServletRequest request) {

        String username = (String) request.getAttribute(AccessFilterBase.ATTR_USERNAME);
        if (username == null) {
            // The filter would have rejected the request before reaching here, so
            // this branch is defensive only.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<UserDocument> userOpt = userService.findByTenantAndName(tenant, username);
        if (userOpt.isEmpty() || userOpt.get().getStatus() != UserStatus.ACTIVE) {
            log.debug("Refresh rejected: user inactive or missing tenant='{}' name='{}'", tenant, username);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // Refresh is a re-mint — same gate as initial login. A user whose
        // loginEnabled flipped to false must not silently extend their
        // session via the refresh cookie.
        if (!userOpt.get().isLoginEnabled()) {
            log.debug("Refresh rejected: loginEnabled=false tenant='{}' name='{}'", tenant, username);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Instant expiresAt = Instant.now().plus(TOKEN_LIFETIME);
        String token = jwtService.createToken(tenant, username, expiresAt);

        log.info("Refreshed JWT tenant='{}' user='{}' expiresAt={}", tenant, username, expiresAt);
        auditService.authTokenRefresh(tenant, username);
        return ResponseEntity.ok(RefreshTokenResponse.builder()
                .token(token)
                .expiresAtTimestamp(expiresAt.toEpochMilli())
                .build());
    }

    private static ResponseEntity<AccessTokenResponse> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    /**
     * Audit-aware rejection. Each failure path in {@link #createToken}
     * routes through here so the audit trail captures the reason while
     * the HTTP response stays uniform (no user-enumeration leak).
     */
    private ResponseEntity<AccessTokenResponse> rejectLogin(
            String tenant, String username, String reason) {
        auditService.authLoginFailure(tenant, username, reason);
        return unauthorized();
    }

    /**
     * Server-side logout — clears the three web-UI cookies by sending
     * {@code Set-Cookie} headers with {@code Max-Age=0}.
     *
     * <p>The {@code HttpOnly} flag on access and refresh cookies means
     * JavaScript cannot delete them itself, so a server round-trip is
     * required. Open by design — a logout call must succeed even when
     * the access token has already expired.
     */
    @PostMapping("/brain/{tenant}/logout")
    public ResponseEntity<Void> logout(@PathVariable("tenant") String tenant) {
        ResponseEntity.HeadersBuilder<?> builder = ResponseEntity.noContent();
        webUiCookieService.clearCookies(builder);
        log.info("Logout tenant='{}'", tenant);
        auditService.authLogout(tenant, null);
        return builder.build();
    }

    private static @org.jspecify.annotations.Nullable String extractRefreshCookie(HttpServletRequest request) {
        if (request == null || request.getCookies() == null) return null;
        for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
            if (!WebUiCookies.REFRESH.equals(cookie.getName())) continue;
            String value = cookie.getValue();
            return (value == null || value.isBlank()) ? null : value.trim();
        }
        return null;
    }
}
