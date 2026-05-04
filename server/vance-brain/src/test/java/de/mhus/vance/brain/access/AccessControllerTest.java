package de.mhus.vance.brain.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.access.AccessTokenRequest;
import de.mhus.vance.api.access.AccessTokenResponse;
import de.mhus.vance.brain.servertool.ServerToolBootstrapService;
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
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Verifies the password-vs-refresh-token credential matrix and the
 * refresh-token issuance gate at {@code POST /brain/{tenant}/access/{username}}.
 *
 * <p>All collaborators are mocked — this is pure controller-logic
 * coverage. End-to-end refresh against the real {@link JwtService} key
 * roundtrip is exercised by {@code JwtServiceTest}.
 */
class AccessControllerTest {

    private static final String TENANT = "acme";
    private static final String USERNAME = "alice";
    private static final String OTHER_TENANT = "other";
    private static final String OTHER_USERNAME = "bob";

    private JwtService jwtService;
    private UserService userService;
    private PasswordService passwordService;
    private HomeBootstrapService homeBootstrapService;
    private ServerToolBootstrapService serverToolBootstrapService;
    private SettingService settingService;
    private AccessController controller;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        userService = mock(UserService.class);
        passwordService = mock(PasswordService.class);
        homeBootstrapService = mock(HomeBootstrapService.class);
        serverToolBootstrapService = mock(ServerToolBootstrapService.class);
        settingService = mock(SettingService.class);

        controller = new AccessController(
                jwtService,
                userService,
                passwordService,
                homeBootstrapService,
                serverToolBootstrapService,
                settingService,
                /* cookieSecure */ true);

        // Default wiring — an active user exists in the tenant with a
        // password hash. Individual tests override what they need.
        UserDocument user = activeUser();
        when(userService.findByTenantAndName(TENANT, USERNAME))
                .thenReturn(Optional.of(user));
        when(passwordService.verify("right-password", "hash")).thenReturn(true);
        when(jwtService.createToken(
                eqTenant(), eqUsername(), anyInstant(), eqType(TokenType.ACCESS)))
                .thenReturn("access-jwt");
        when(jwtService.createToken(
                eqTenant(), eqUsername(), anyInstant(), eqType(TokenType.REFRESH)))
                .thenReturn("refresh-jwt");
    }

    // ──────────────── Credential XOR ────────────────

    @Test
    void rejects_whenNeitherPasswordNorRefreshTokenSet() {
        AccessTokenRequest req = AccessTokenRequest.builder().build();

        ResponseEntity<AccessTokenResponse> resp = controller.createToken(TENANT, USERNAME, req, emptyRequest());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(jwtService, never()).createToken(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejects_whenBothPasswordAndRefreshTokenSet() {
        AccessTokenRequest req = AccessTokenRequest.builder()
                .password("right-password")
                .refreshToken("anything")
                .build();

        ResponseEntity<AccessTokenResponse> resp = controller.createToken(TENANT, USERNAME, req, emptyRequest());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ──────────────── Password login ────────────────

    @Test
    void passwordLogin_withRequestRefreshTokenFalse_omitsRefreshFromResponse() {
        AccessTokenRequest req = AccessTokenRequest.builder()
                .password("right-password")
                .build();

        ResponseEntity<AccessTokenResponse> resp = controller.createToken(TENANT, USERNAME, req, emptyRequest());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        AccessTokenResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getToken()).isEqualTo("access-jwt");
        assertThat(body.getRefreshToken()).isNull();
        assertThat(body.getRefreshTokenExpiresAtTimestamp()).isNull();
    }

    @Test
    void passwordLogin_withRequestRefreshTokenTrue_returnsBothTokens() {
        AccessTokenRequest req = AccessTokenRequest.builder()
                .password("right-password")
                .requestRefreshToken(true)
                .build();

        ResponseEntity<AccessTokenResponse> resp = controller.createToken(TENANT, USERNAME, req, emptyRequest());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        AccessTokenResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getToken()).isEqualTo("access-jwt");
        assertThat(body.getRefreshToken()).isEqualTo("refresh-jwt");
        assertThat(body.getRefreshTokenExpiresAtTimestamp()).isNotNull();

        long now = Instant.now().toEpochMilli();
        long thirtyDaysMs = 30L * 24 * 60 * 60 * 1000;
        // Refresh token must expire roughly 30 days from now (within
        // a generous 1-minute window for clock + scheduler jitter).
        assertThat(body.getRefreshTokenExpiresAtTimestamp())
                .isBetween(now + thirtyDaysMs - 60_000, now + thirtyDaysMs + 60_000);
    }

    @Test
    void passwordLogin_withWrongPassword_returns401() {
        AccessTokenRequest req = AccessTokenRequest.builder()
                .password("wrong-password")
                .build();

        ResponseEntity<AccessTokenResponse> resp = controller.createToken(TENANT, USERNAME, req, emptyRequest());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void passwordLogin_withInactiveUser_returns401() {
        UserDocument inactive = UserDocument.builder()
                .tenantId(TENANT)
                .name(USERNAME)
                .passwordHash("hash")
                .status(UserStatus.DISABLED)
                .build();
        when(userService.findByTenantAndName(TENANT, USERNAME))
                .thenReturn(Optional.of(inactive));

        AccessTokenRequest req = AccessTokenRequest.builder()
                .password("right-password")
                .build();

        ResponseEntity<AccessTokenResponse> resp = controller.createToken(TENANT, USERNAME, req, emptyRequest());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void passwordLogin_withUnknownUser_returns401() {
        when(userService.findByTenantAndName(TENANT, USERNAME))
                .thenReturn(Optional.empty());

        AccessTokenRequest req = AccessTokenRequest.builder()
                .password("right-password")
                .build();

        ResponseEntity<AccessTokenResponse> resp = controller.createToken(TENANT, USERNAME, req, emptyRequest());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ──────────────── Refresh-token login ────────────────

    @Test
    void refreshLogin_withValidRefreshToken_returns200() {
        when(jwtService.validateToken("good-refresh"))
                .thenReturn(Optional.of(refreshClaims(TENANT, USERNAME)));

        AccessTokenRequest req = AccessTokenRequest.builder()
                .refreshToken("good-refresh")
                .build();

        ResponseEntity<AccessTokenResponse> resp = controller.createToken(TENANT, USERNAME, req, emptyRequest());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getToken()).isEqualTo("access-jwt");
        // No request flag → no refresh in response.
        assertThat(resp.getBody().getRefreshToken()).isNull();

        // Password verification must be skipped on the refresh path.
        verify(passwordService, never()).verify(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void refreshLogin_canRollOverWhenRequested() {
        when(jwtService.validateToken("good-refresh"))
                .thenReturn(Optional.of(refreshClaims(TENANT, USERNAME)));

        AccessTokenRequest req = AccessTokenRequest.builder()
                .refreshToken("good-refresh")
                .requestRefreshToken(true)
                .build();

        ResponseEntity<AccessTokenResponse> resp = controller.createToken(TENANT, USERNAME, req, emptyRequest());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getRefreshToken()).isEqualTo("refresh-jwt");
    }

    @Test
    void refreshLogin_rejectsAccessTokenInRefreshSlot() {
        // Caller mistakenly forwarded their access token. Must not be
        // accepted as a refresh credential.
        when(jwtService.validateToken("an-access-token"))
                .thenReturn(Optional.of(accessClaims(TENANT, USERNAME)));

        AccessTokenRequest req = AccessTokenRequest.builder()
                .refreshToken("an-access-token")
                .build();

        ResponseEntity<AccessTokenResponse> resp = controller.createToken(TENANT, USERNAME, req, emptyRequest());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshLogin_rejectsTenantMismatch() {
        // Refresh token was minted for a different tenant. Cross-tenant
        // reuse is a forgery vector — reject.
        when(jwtService.validateToken("foreign-tenant-refresh"))
                .thenReturn(Optional.of(refreshClaims(OTHER_TENANT, USERNAME)));

        AccessTokenRequest req = AccessTokenRequest.builder()
                .refreshToken("foreign-tenant-refresh")
                .build();

        ResponseEntity<AccessTokenResponse> resp = controller.createToken(TENANT, USERNAME, req, emptyRequest());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshLogin_rejectsUsernameMismatch() {
        // Same tenant, but the token was minted for a different user.
        when(jwtService.validateToken("foreign-user-refresh"))
                .thenReturn(Optional.of(refreshClaims(TENANT, OTHER_USERNAME)));

        AccessTokenRequest req = AccessTokenRequest.builder()
                .refreshToken("foreign-user-refresh")
                .build();

        ResponseEntity<AccessTokenResponse> resp = controller.createToken(TENANT, USERNAME, req, emptyRequest());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshLogin_rejectsInvalidToken() {
        when(jwtService.validateToken("garbage"))
                .thenReturn(Optional.empty());

        AccessTokenRequest req = AccessTokenRequest.builder()
                .refreshToken("garbage")
                .build();

        ResponseEntity<AccessTokenResponse> resp = controller.createToken(TENANT, USERNAME, req, emptyRequest());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshLogin_rejectsInactiveUser() {
        // Token is valid and identity matches, but the user has been
        // deactivated since the refresh token was issued.
        when(jwtService.validateToken("good-refresh"))
                .thenReturn(Optional.of(refreshClaims(TENANT, USERNAME)));
        UserDocument inactive = UserDocument.builder()
                .tenantId(TENANT)
                .name(USERNAME)
                .passwordHash("hash")
                .status(UserStatus.DISABLED)
                .build();
        when(userService.findByTenantAndName(TENANT, USERNAME))
                .thenReturn(Optional.of(inactive));

        AccessTokenRequest req = AccessTokenRequest.builder()
                .refreshToken("good-refresh")
                .build();

        ResponseEntity<AccessTokenResponse> resp = controller.createToken(TENANT, USERNAME, req, emptyRequest());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ──────────────── Web-UI cookies ────────────────

    @Test
    void noCookies_byDefault() {
        // Without requestCookies the response carries the JSON body
        // only — the CLI shape stays untouched.
        AccessTokenRequest req = AccessTokenRequest.builder()
                .password("right-password")
                .build();

        ResponseEntity<AccessTokenResponse> resp = controller.createToken(TENANT, USERNAME, req, emptyRequest());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().get(HttpHeaders.SET_COOKIE)).isNullOrEmpty();
    }

    @Test
    void requestCookies_setsAccessAndDataCookies() {
        AccessTokenRequest req = AccessTokenRequest.builder()
                .password("right-password")
                .requestCookies(true)
                .build();

        ResponseEntity<AccessTokenResponse> resp = controller.createToken(TENANT, USERNAME, req, emptyRequest());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> setCookies = resp.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).isNotNull();

        // Access cookie: HttpOnly, Secure, SameSite=Strict, Path=/.
        String access = findCookie(setCookies, WebUiCookies.ACCESS);
        assertThat(access).contains("HttpOnly");
        assertThat(access).contains("Secure");
        assertThat(access).contains("SameSite=Strict");
        assertThat(access).contains("Path=/");
        assertThat(cookieValue(access)).isEqualTo("access-jwt");

        // No refresh requested → no refresh cookie.
        assertThat(findCookieOrNull(setCookies, WebUiCookies.REFRESH)).isNull();

        // Data cookie: NOT HttpOnly so the SPA can read it.
        String data = findCookie(setCookies, WebUiCookies.DATA);
        assertThat(data).doesNotContain("HttpOnly");
        assertThat(data).contains("Secure");
        assertThat(data).contains("SameSite=Strict");
    }

    @Test
    void requestCookies_withRefreshToken_setsAllThreeCookies() {
        AccessTokenRequest req = AccessTokenRequest.builder()
                .password("right-password")
                .requestCookies(true)
                .requestRefreshToken(true)
                .build();

        ResponseEntity<AccessTokenResponse> resp = controller.createToken(TENANT, USERNAME, req, emptyRequest());

        List<String> setCookies = resp.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).isNotNull();
        // Three cookies: access + refresh + data.
        assertThat(setCookies).hasSize(3);

        String refresh = findCookie(setCookies, WebUiCookies.REFRESH);
        assertThat(refresh).contains("HttpOnly");
        assertThat(cookieValue(refresh)).isEqualTo("refresh-jwt");
    }

    @Test
    void requestCookies_includesWebUiSettings_whenRequested() {
        Map<String, String> savedSettings = new LinkedHashMap<>();
        savedSettings.put("webui.theme", "dark");
        savedSettings.put("webui.language", "de");
        when(settingService.findUserSettingsByPrefix(TENANT, USERNAME, WebUiCookies.SETTINGS_PREFIX))
                .thenReturn(savedSettings);

        AccessTokenRequest req = AccessTokenRequest.builder()
                .password("right-password")
                .requestCookies(true)
                .includeWebUiSettings(true)
                .build();

        ResponseEntity<AccessTokenResponse> resp = controller.createToken(TENANT, USERNAME, req, emptyRequest());

        List<String> setCookies = resp.getHeaders().get(HttpHeaders.SET_COOKIE);
        String data = findCookie(setCookies, WebUiCookies.DATA);
        String decoded = decodeDataCookie(data);

        // The settings are embedded as JSON inside the data cookie —
        // we don't reparse via Jackson here (that would import yet
        // another dependency); a substring check is enough to verify
        // the prefix lookup ran and the values landed.
        assertThat(decoded).contains("\"webui.theme\":\"dark\"");
        assertThat(decoded).contains("\"webui.language\":\"de\"");
        assertThat(decoded).contains("\"username\":\"" + USERNAME + "\"");
        assertThat(decoded).contains("\"tenantId\":\"" + TENANT + "\"");
    }

    @Test
    void requestCookies_omitsSettings_whenIncludeFlagFalse() {
        // Even when the user has settings stored, requestCookies
        // alone (without includeWebUiSettings) keeps them out of the
        // payload — the SPA opts in explicitly.
        Map<String, String> savedSettings = new LinkedHashMap<>();
        savedSettings.put("webui.theme", "dark");
        when(settingService.findUserSettingsByPrefix(TENANT, USERNAME, WebUiCookies.SETTINGS_PREFIX))
                .thenReturn(savedSettings);

        AccessTokenRequest req = AccessTokenRequest.builder()
                .password("right-password")
                .requestCookies(true)
                .build();

        ResponseEntity<AccessTokenResponse> resp = controller.createToken(TENANT, USERNAME, req, emptyRequest());

        String data = findCookie(resp.getHeaders().get(HttpHeaders.SET_COOKIE), WebUiCookies.DATA);
        String decoded = decodeDataCookie(data);

        assertThat(decoded).doesNotContain("webui.theme");
        // findUserSettingsByPrefix must not be queried unnecessarily.
        verify(settingService, never()).findUserSettingsByPrefix(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void cookies_canBeFlaggedNotSecure_forLocalDev() {
        // Override the cookie-secure flag — this is the dev-mode HTTP
        // path. Production must keep it true.
        AccessController insecure = new AccessController(
                jwtService, userService, passwordService,
                homeBootstrapService, serverToolBootstrapService, settingService,
                /* cookieSecure */ false);

        AccessTokenRequest req = AccessTokenRequest.builder()
                .password("right-password")
                .requestCookies(true)
                .build();

        ResponseEntity<AccessTokenResponse> resp = insecure.createToken(
                TENANT, USERNAME, req, emptyRequest());

        String access = findCookie(resp.getHeaders().get(HttpHeaders.SET_COOKIE), WebUiCookies.ACCESS);
        assertThat(access).doesNotContain("Secure");
        // HttpOnly must still be there — that flag is independent of
        // the transport.
        assertThat(access).contains("HttpOnly");
    }

    // ──────────────── Refresh-cookie silent login ────────────────

    @Test
    void silentLogin_viaRefreshCookie_succeedsWhenBodyEmpty() {
        // Web-UI silent re-mint: the SPA POSTs to /access/{username}
        // with no body credential; the browser ships the
        // {@code vance_refresh} cookie automatically. Server must
        // accept the cookie as the credential and re-issue tokens.
        when(jwtService.validateToken("cookie-refresh-jwt"))
                .thenReturn(Optional.of(refreshClaims(TENANT, USERNAME)));

        AccessTokenRequest req = AccessTokenRequest.builder()
                .requestCookies(true)
                .build();

        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getCookies()).thenReturn(new Cookie[]{
                new Cookie(WebUiCookies.REFRESH, "cookie-refresh-jwt"),
        });

        ResponseEntity<AccessTokenResponse> resp = controller.createToken(
                TENANT, USERNAME, req, httpRequest);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getToken()).isEqualTo("access-jwt");
        // Server-set cookies should be present.
        assertThat(resp.getHeaders().get(HttpHeaders.SET_COOKIE)).isNotEmpty();
    }

    @Test
    void silentLogin_rejects_whenNoBodyAndNoRefreshCookie() {
        AccessTokenRequest req = AccessTokenRequest.builder().build();

        ResponseEntity<AccessTokenResponse> resp = controller.createToken(
                TENANT, USERNAME, req, emptyRequest());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void silentLogin_rejects_whenCookieIsAccessTokenNotRefresh() {
        // Cookie present but type=ACCESS — caller's credentials cookie
        // (vance_access) ended up in the wrong slot or somehow leaked.
        // Same gate as the body-path: only REFRESH is acceptable.
        when(jwtService.validateToken("an-access-jwt"))
                .thenReturn(Optional.of(accessClaims(TENANT, USERNAME)));

        AccessTokenRequest req = AccessTokenRequest.builder().build();

        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getCookies()).thenReturn(new Cookie[]{
                new Cookie(WebUiCookies.REFRESH, "an-access-jwt"),
        });

        ResponseEntity<AccessTokenResponse> resp = controller.createToken(
                TENANT, USERNAME, req, httpRequest);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ──────────────── Logout ────────────────

    @Test
    void logout_clearsAllThreeCookies() {
        ResponseEntity<Void> resp = controller.logout(TENANT);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        List<String> setCookies = resp.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).hasSize(3);
        for (String name : List.of(WebUiCookies.ACCESS, WebUiCookies.REFRESH, WebUiCookies.DATA)) {
            String header = findCookie(setCookies, name);
            // Max-Age=0 is what tells the browser to delete the cookie.
            assertThat(header).contains("Max-Age=0");
        }
    }

    // ──────────────── Helpers ────────────────

    private static HttpServletRequest emptyRequest() {
        // Stand-in for the HttpServletRequest the controller expects.
        // No cookies, no headers — used by every test that doesn't
        // exercise the cookie fallback.
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getCookies()).thenReturn(null);
        return req;
    }

    private static String findCookie(List<String> setCookies, String name) {
        String c = findCookieOrNull(setCookies, name);
        if (c == null) {
            throw new AssertionError("Cookie '" + name + "' not in Set-Cookie headers: " + setCookies);
        }
        return c;
    }

    private static String findCookieOrNull(List<String> setCookies, String name) {
        if (setCookies == null) return null;
        for (String header : setCookies) {
            if (header.startsWith(name + "=")) return header;
        }
        return null;
    }

    private static String cookieValue(String setCookieHeader) {
        int eq = setCookieHeader.indexOf('=');
        int semi = setCookieHeader.indexOf(';');
        return setCookieHeader.substring(eq + 1, semi < 0 ? setCookieHeader.length() : semi);
    }

    private static String decodeDataCookie(String setCookieHeader) {
        return URLDecoder.decode(cookieValue(setCookieHeader), StandardCharsets.UTF_8);
    }

    private static UserDocument activeUser() {
        return UserDocument.builder()
                .tenantId(TENANT)
                .name(USERNAME)
                .title("Alice the Active")
                .passwordHash("hash")
                .status(UserStatus.ACTIVE)
                .build();
    }

    private static VanceJwtClaims refreshClaims(String tenantId, String username) {
        return new VanceJwtClaims(username, tenantId,
                Instant.now().minusSeconds(60),
                Instant.now().plusSeconds(86_400),
                TokenType.REFRESH);
    }

    private static VanceJwtClaims accessClaims(String tenantId, String username) {
        return new VanceJwtClaims(username, tenantId,
                Instant.now().minusSeconds(60),
                Instant.now().plusSeconds(86_400),
                TokenType.ACCESS);
    }

    // Mockito argument-matcher shorthands — keep the @BeforeEach stubs
    // readable. Each one is just an alias for the corresponding any*().
    private static String eqTenant() { return org.mockito.ArgumentMatchers.eq(TENANT); }
    private static String eqUsername() { return org.mockito.ArgumentMatchers.eq(USERNAME); }
    private static Instant anyInstant() { return org.mockito.ArgumentMatchers.any(Instant.class); }
    private static TokenType eqType(TokenType t) { return org.mockito.ArgumentMatchers.eq(t); }
}
