package de.mhus.vance.brain.access;

import de.mhus.vance.api.access.WebUiSessionData;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.access.WebUiCookies;
import de.mhus.vance.shared.jwt.JwtService;
import de.mhus.vance.shared.jwt.TokenType;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import de.mhus.vance.shared.settings.LanguageResolver;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.user.UserDocument;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds and attaches the three web-UI cookies (access / refresh / data).
 *
 * <p>Used by {@link AccessController} on the login + silent-refresh path
 * (mints all three cookies in lockstep) and by mutation endpoints — most
 * notably {@code ProfileController} — that need to re-issue just the
 * data cookie so settings written to MongoDB also land in the cookie
 * the SPA reads on the next page load.
 *
 * <p>Cookie sticky-bits:
 * <ul>
 *   <li>{@code HttpOnly} on access + refresh — JS cannot read them,
 *       so an XSS payload can't exfiltrate the credential.</li>
 *   <li>{@code SameSite=Lax} — the cookies travel on same-origin
 *       requests and on top-level cross-site GET navigations.
 *       Strict would block the OAuth callback flow.</li>
 *   <li>{@code Secure} — toggled by {@code vance.web.cookies.secure}
 *       (default {@code true}). Production must keep it true.</li>
 *   <li>{@code Path=/} — every brain endpoint receives the cookies.</li>
 * </ul>
 */
@Service
@Slf4j
public class WebUiCookieService {

    /**
     * Settings that ship in the data cookie even though they sit
     * outside the {@code webui.*} prefix. Keep this small — every
     * entry costs cookie size on every request, and any value here
     * leaks into the JS-readable cookie payload.
     *
     * <p>{@code chat.language} is here because the Web-UI chat composer
     * needs the resolved language at mount time (for speech
     * recognition) without an extra round-trip to {@code /profile}.
     *
     * <p>All entries in this set are <b>user-scoped</b>. Project-scoped
     * config (infrastructure-style settings that depend on the
     * currently-active project) belongs to
     * {@code SettingsCascadeController} instead — the cookie is
     * minted once at login and cannot follow project switches.
     */
    private static final Set<String> EXTRA_COOKIE_SETTING_KEYS = Set.of(
            LanguageResolver.Keys.CHAT_LANGUAGE);

    private final JwtService jwtService;
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
    public WebUiCookieService(JwtService jwtService,
                              SettingService settingService,
                              @Value("${vance.web.cookies.secure:true}") boolean cookieSecure) {
        this.jwtService = jwtService;
        this.settingService = settingService;
        this.cookieSecure = cookieSecure;
    }

    /**
     * Mints all three cookies for the login / silent-refresh path. The
     * refresh cookie is only set when a refresh token was actually
     * issued.
     *
     * @param includeWebUiSettings if {@code true}, the data cookie's
     *                             payload includes the user's
     *                             {@code webui.*} settings — the SPA
     *                             needs them to render its post-login
     *                             state without an extra REST call.
     */
    public void attachLoginCookies(
            ResponseEntity.BodyBuilder responseBuilder,
            UserDocument user,
            String accessToken,
            Instant accessExpiresAt,
            @Nullable String refreshToken,
            @Nullable Instant refreshExpiresAt,
            boolean includeWebUiSettings) {

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

        attachDataCookie(responseBuilder, user, accessExpiresAt, refreshExpiresAt,
                includeWebUiSettings);
    }

    /**
     * Re-mint the data cookie for an already-authenticated request.
     * Used by mutation endpoints (Profile settings, display-name, …)
     * so the {@code webui.*} snapshot the SPA reads stays in sync with
     * what's in MongoDB without forcing a re-login.
     *
     * <p>Access-token expiry comes from the {@link VanceJwtClaims}
     * deposited by {@link AccessFilterBase} on every authenticated
     * request. Refresh-token expiry is read from the {@code vance_refresh}
     * cookie — present on every browser request because the
     * {@code HttpOnly} cookie auto-attaches — and parsed via
     * {@link JwtService} so we trust its expiry the same way the auth
     * filter trusts the access claims.
     *
     * <p>If no refresh cookie is present (CLI clients hitting this
     * endpoint via bearer header) the data cookie's lifetime matches
     * the access token's — the SPA cookie path is the one that needs
     * the longer lifetime anyway.
     */
    public void refreshDataCookie(
            HttpServletRequest request,
            ResponseEntity.HeadersBuilder<?> responseBuilder,
            UserDocument user) {
        Instant accessExpiresAt = accessExpiryFromRequest(request);
        Instant refreshExpiresAt = refreshExpiryFromRequest(request);
        attachDataCookie(responseBuilder, user, accessExpiresAt, refreshExpiresAt, true);
    }

    /**
     * Clear all three web-UI cookies. Sends {@code Set-Cookie} headers
     * with {@code Max-Age=0} so the browser drops them immediately. The
     * {@code HttpOnly} flag stays asymmetric — same as the mint path —
     * because the SPA still needs the data cookie to be readable by JS,
     * but credentials must stay out of JS reach until the very last
     * moment.
     */
    public void clearCookies(ResponseEntity.HeadersBuilder<?> responseBuilder) {
        for (String name : new String[]{WebUiCookies.ACCESS, WebUiCookies.REFRESH, WebUiCookies.DATA}) {
            ResponseCookie expired = baseCookie(name, "")
                    .httpOnly(!WebUiCookies.DATA.equals(name))
                    .maxAge(0)
                    .build();
            responseBuilder.header(HttpHeaders.SET_COOKIE, expired.toString());
        }
    }

    // ─── Internals ────────────────────────────────────────────────────────

    private void attachDataCookie(
            ResponseEntity.HeadersBuilder<?> responseBuilder,
            UserDocument user,
            Instant accessExpiresAt,
            @Nullable Instant refreshExpiresAt,
            boolean includeWebUiSettings) {

        Map<String, String> webUiSettings = includeWebUiSettings
                ? loadCookieSettings(user.getTenantId(), user.getName())
                : Map.of();

        WebUiSessionData sessionData = WebUiSessionData.builder()
                .username(user.getName())
                .tenantId(user.getTenantId())
                .displayName(user.getTitle())
                .accessExpiresAtTimestamp(accessExpiresAt.toEpochMilli())
                .refreshExpiresAtTimestamp(refreshExpiresAt == null
                        ? null
                        : refreshExpiresAt.toEpochMilli())
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

    /**
     * Collect all settings that ship in the data cookie — the
     * {@code webui.*} prefix scan plus the explicit
     * {@link #EXTRA_COOKIE_SETTING_KEYS} allowlist for cookie-visible
     * extras like {@code chat.language}.
     */
    private Map<String, String> loadCookieSettings(String tenantId, String username) {
        Map<String, String> result = new LinkedHashMap<>(
                settingService.findUserSettingsByPrefix(
                        tenantId, username, WebUiCookies.SETTINGS_PREFIX));
        for (String key : EXTRA_COOKIE_SETTING_KEYS) {
            String v = settingService.getUserStringValue(tenantId, username, key);
            if (v != null) result.put(key, v);
        }
        return result;
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String name, String value) {
        return ResponseCookie.from(name, value)
                .secure(cookieSecure)
                .sameSite("Lax")
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

    private static Instant accessExpiryFromRequest(HttpServletRequest request) {
        Object attr = request.getAttribute(AccessFilterBase.ATTR_CLAIMS);
        if (!(attr instanceof VanceJwtClaims claims)) {
            throw new IllegalStateException(
                    "No verified JWT claims on request — refreshDataCookie called "
                            + "from an unauthenticated endpoint?");
        }
        Instant exp = claims.expiresAt();
        if (exp == null) {
            // Non-expiring access tokens are not used in practice (every
            // createToken path sets an expiry). Defensive fall-back: a
            // 24h window keeps the data cookie usable.
            return Instant.now().plus(Duration.ofHours(24));
        }
        return exp;
    }

    private @Nullable Instant refreshExpiryFromRequest(HttpServletRequest request) {
        String refreshToken = extractRefreshCookie(request);
        if (refreshToken == null) return null;
        VanceJwtClaims claims = jwtService.validateToken(refreshToken).orElse(null);
        if (claims == null) {
            // Refresh cookie present but invalid — log + ignore. The
            // data cookie just falls back to the shorter access-token
            // lifetime; the SPA will hit /access on the next mount.
            log.debug("Ignoring invalid refresh cookie when refreshing data cookie");
            return null;
        }
        if (claims.tokenType() != TokenType.REFRESH) return null;
        return claims.expiresAt();
    }

    private static @Nullable String extractRefreshCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (!WebUiCookies.REFRESH.equals(cookie.getName())) continue;
            String value = cookie.getValue();
            return (value == null || value.isBlank()) ? null : value.trim();
        }
        return null;
    }
}
