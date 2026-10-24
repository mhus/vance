package de.mhus.vance.brain.access;

import de.mhus.vance.api.access.WebUiSessionData;
import de.mhus.vance.shared.access.WebUiCookies;
import de.mhus.vance.shared.user.UserDocument;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
 * Builds and attaches the three web-UI cookies (access / refresh / data) on
 * the login and silent-refresh paths.
 *
 * <p>The data cookie carries <b>authentication facts only</b> — username,
 * tenant and the two expiry timestamps the SPA needs to schedule a silent
 * re-mint and to route before its first request. Everything that can change
 * while the cookie lives (display name, settings, roles, quotas) comes from
 * {@code GET /brain/{tenant}/me} instead: a cookie is only re-issued on login
 * and refresh, so anything else in it would go stale the moment it is changed
 * somewhere else.
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

    private final boolean cookieSecure;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    /**
     * @param cookieSecure default {@code true}. Set to {@code false} in
     *                     local dev over plain HTTP so the browser
     *                     accepts the cookies. Production must keep it
     *                     {@code true}, otherwise the credential
     *                     cookie can be sniffed off the wire.
     */
    public WebUiCookieService(@Value("${vance.web.cookies.secure:true}") boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }

    /**
     * Mints all three cookies for the login / silent-refresh path. The
     * refresh cookie is only set when a refresh token was actually
     * issued.
     */
    public void attachLoginCookies(
            ResponseEntity.BodyBuilder responseBuilder,
            UserDocument user,
            String accessToken,
            Instant accessExpiresAt,
            @Nullable String refreshToken,
            @Nullable Instant refreshExpiresAt) {

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

        attachDataCookie(responseBuilder, user, accessExpiresAt, refreshExpiresAt);
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
        for (String name : new String[] {WebUiCookies.ACCESS, WebUiCookies.REFRESH, WebUiCookies.DATA}) {
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
            @Nullable Instant refreshExpiresAt) {

        WebUiSessionData sessionData = WebUiSessionData.builder()
                .username(user.getName())
                .tenantId(user.getTenantId())
                .accessExpiresAtTimestamp(accessExpiresAt.toEpochMilli())
                .refreshExpiresAtTimestamp(refreshExpiresAt == null ? null : refreshExpiresAt.toEpochMilli())
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
            // The DTO is a plain POJO and never throws — defensive only.
            // Drop the data cookie rather than emit broken JSON; the SPA
            // then treats the session as absent and re-authenticates.
            log.warn(
                    "Failed to serialise WebUiSessionData for tenant='{}' user='{}': {}",
                    sessionData.getTenantId(),
                    sessionData.getUsername(),
                    e.getMessage());
            return "";
        }
    }
}
