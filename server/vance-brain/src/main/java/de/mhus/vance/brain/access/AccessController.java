package de.mhus.vance.brain.access;

import de.mhus.vance.api.access.AccessTokenRequest;
import de.mhus.vance.api.access.AccessTokenResponse;
import de.mhus.vance.api.access.RefreshTokenResponse;
import de.mhus.vance.api.access.WebUiSessionData;
import de.mhus.vance.brain.servertool.ServerToolBootstrapService;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.access.WebUiCookies;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.jwt.JwtService;
import de.mhus.vance.shared.jwt.TokenType;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import de.mhus.vance.shared.password.PasswordService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

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
    private final ServerToolBootstrapService serverToolBootstrapService;
    private final SettingService settingService;
    private final boolean cookieSecure;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    /**
     * @param cookieSecure default {@code true}. Set to {@code false} in
     *                     local non-HTTPS development so the browser
     *                     accepts the cookies. Production must keep it
     *                     true — otherwise the {@code HttpOnly} access
     *                     cookie can be sniffed off the wire.
     */
    public AccessController(JwtService jwtService,
                            UserService userService,
                            PasswordService passwordService,
                            HomeBootstrapService homeBootstrapService,
                            ServerToolBootstrapService serverToolBootstrapService,
                            SettingService settingService,
                            @Value("${vance.web.cookies.secure:true}") boolean cookieSecure) {
        this.jwtService = jwtService;
        this.userService = userService;
        this.passwordService = passwordService;
        this.homeBootstrapService = homeBootstrapService;
        this.serverToolBootstrapService = serverToolBootstrapService;
        this.settingService = settingService;
        this.cookieSecure = cookieSecure;
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
            return unauthorized();
        }

        Optional<UserDocument> userOpt = userService.findByTenantAndName(tenant, username);
        if (userOpt.isEmpty()) {
            log.debug("Login rejected: unknown user tenant='{}' name='{}'", tenant, username);
            return unauthorized();
        }
        UserDocument user = userOpt.get();

        if (user.getStatus() != UserStatus.ACTIVE) {
            log.debug("Login rejected: status={} tenant='{}' name='{}'", user.getStatus(), tenant, username);
            return unauthorized();
        }

        if (hasPassword) {
            String hash = user.getPasswordHash();
            if (hash == null) {
                log.debug("Login rejected: no password hash tenant='{}' name='{}'", tenant, username);
                return unauthorized();
            }
            if (!passwordService.verify(request.getPassword(), hash)) {
                log.debug("Login rejected: bad password tenant='{}' name='{}'", tenant, username);
                return unauthorized();
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
                return unauthorized();
            }
            if (claims.tokenType() != TokenType.REFRESH) {
                log.debug("Login rejected: presented {} token in refreshToken slot (tenant='{}' name='{}')",
                        claims.tokenType(), tenant, username);
                return unauthorized();
            }
            if (!tenant.equals(claims.tenantId()) || !username.equals(claims.username())) {
                log.debug("Login rejected: refresh token identity mismatch — path tenant='{}' user='{}', token tenant='{}' user='{}'",
                        tenant, username, claims.tenantId(), claims.username());
                return unauthorized();
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
        homeBootstrapService.ensureVance(tenant);
        // Bundled server-tool defaults inside _vance. Idempotent —
        // existing rows are left untouched so tenant edits survive.
        serverToolBootstrapService.ensureSystemTools(tenant);

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

        ResponseEntity.BodyBuilder responseEntityBuilder = ResponseEntity.ok();
        if (request.isRequestCookies()) {
            attachCookies(responseEntityBuilder, user, request,
                    token, expiresAt, refreshToken, refreshExpiresAt);
        }
        return responseEntityBuilder.body(responseBuilder.build());
    }

    /**
     * Builds and attaches the three web-UI cookies. Always sets the
     * access cookie and the JS-readable {@code data} cookie when the
     * caller asked for cookies; the refresh cookie only when a refresh
     * token was actually minted.
     *
     * <p>Cookie sticky-bits:
     * <ul>
     *   <li>{@code HttpOnly} on access + refresh — JS cannot read them,
     *       so an XSS payload can't exfiltrate the credential.</li>
     *   <li>{@code SameSite=Strict} — the cookies travel only on
     *       same-origin requests, blocking CSRF and cross-site
     *       inclusion. The web UI is same-origin with the brain, so
     *       this is the right level.</li>
     *   <li>{@code Secure} — toggled by {@code vance.web.cookies.secure}
     *       (default {@code true}). Production must keep it true.</li>
     *   <li>{@code Path=/} — every brain endpoint receives the cookies.
     *       Tightening the refresh cookie's path to {@code /brain/...}
     *       would block automatic re-mint from arbitrary editors, so
     *       we keep them all path-wide.</li>
     * </ul>
     */
    private void attachCookies(
            ResponseEntity.BodyBuilder responseBuilder,
            UserDocument user,
            AccessTokenRequest request,
            String accessToken,
            Instant accessExpiresAt,
            String refreshToken,
            Instant refreshExpiresAt) {

        ResponseCookie access = baseCookie(WebUiCookies.ACCESS, accessToken)
                .httpOnly(true)
                .maxAge(Duration.between(Instant.now(), accessExpiresAt))
                .build();
        responseBuilder.header(HttpHeaders.SET_COOKIE, access.toString());

        if (refreshToken != null && refreshExpiresAt != null) {
            ResponseCookie refresh = baseCookie(WebUiCookies.REFRESH, refreshToken)
                    .httpOnly(true)
                    .maxAge(Duration.between(Instant.now(), refreshExpiresAt))
                    .build();
            responseBuilder.header(HttpHeaders.SET_COOKIE, refresh.toString());
        }

        Map<String, String> webUiSettings = request.isIncludeWebUiSettings()
                ? settingService.findUserSettingsByPrefix(
                        user.getTenantId(), user.getName(), WebUiCookies.SETTINGS_PREFIX)
                : Map.of();

        WebUiSessionData sessionData = WebUiSessionData.builder()
                .username(user.getName())
                .tenantId(user.getTenantId())
                .displayName(user.getTitle())
                .accessExpiresAtTimestamp(accessExpiresAt.toEpochMilli())
                .refreshExpiresAtTimestamp(refreshExpiresAt == null ? null : refreshExpiresAt.toEpochMilli())
                .webUiSettings(webUiSettings)
                .build();

        // The data cookie's lifetime mirrors whichever credential cookie
        // outlives it — the SPA reads expiry timestamps from it to
        // schedule silent re-mints. If the refresh cookie isn't issued
        // we fall back to the access lifetime; the SPA then knows it
        // must redirect to login when the access cookie expires.
        Instant dataExpiresAt = refreshExpiresAt != null ? refreshExpiresAt : accessExpiresAt;

        ResponseCookie data = baseCookie(WebUiCookies.DATA, encodeSessionData(sessionData))
                // Not HttpOnly — the SPA reads it on every page load.
                .httpOnly(false)
                .maxAge(Duration.between(Instant.now(), dataExpiresAt))
                .build();
        responseBuilder.header(HttpHeaders.SET_COOKIE, data.toString());
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String name, String value) {
        return ResponseCookie.from(name, value)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/");
    }

    /**
     * URL-encodes a JSON-serialised {@link WebUiSessionData}. Cookies
     * may not contain raw whitespace / special characters; URL encoding
     * keeps the payload ASCII-safe at the cost of a single decode call
     * on the SPA side.
     */
    private String encodeSessionData(WebUiSessionData sessionData) {
        try {
            String json = objectMapper.writeValueAsString(sessionData);
            return URLEncoder.encode(json, StandardCharsets.UTF_8);
        } catch (JacksonException e) {
            // The DTO is fully POJO and never throws — this branch is
            // defensive only. Fall back to a minimal payload so the
            // SPA at least has identity, even if settings are missing.
            log.warn("Failed to serialise WebUiSessionData for tenant='{}' user='{}': {}",
                    sessionData.getTenantId(), sessionData.getUsername(), e.getMessage());
            return URLEncoder.encode(
                    "{\"username\":\"" + sessionData.getUsername()
                            + "\",\"tenantId\":\"" + sessionData.getTenantId() + "\"}",
                    StandardCharsets.UTF_8);
        }
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

        Instant expiresAt = Instant.now().plus(TOKEN_LIFETIME);
        String token = jwtService.createToken(tenant, username, expiresAt);

        log.info("Refreshed JWT tenant='{}' user='{}' expiresAt={}", tenant, username, expiresAt);
        return ResponseEntity.ok(RefreshTokenResponse.builder()
                .token(token)
                .expiresAtTimestamp(expiresAt.toEpochMilli())
                .build());
    }

    private static ResponseEntity<AccessTokenResponse> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
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
        for (String name : new String[]{WebUiCookies.ACCESS, WebUiCookies.REFRESH, WebUiCookies.DATA}) {
            ResponseCookie expired = ResponseCookie.from(name, "")
                    .secure(cookieSecure)
                    .sameSite("Strict")
                    .path("/")
                    .httpOnly(!WebUiCookies.DATA.equals(name))
                    .maxAge(0)
                    .build();
            builder.header(HttpHeaders.SET_COOKIE, expired.toString());
        }
        log.info("Logout tenant='{}'", tenant);
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
