package de.mhus.vance.brain.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.jwt.JwtService;
import de.mhus.vance.shared.jwt.TokenType;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The Brain's auth-bypass allowlist — the highest-leverage security surface,
 * where a too-wide regex or a regression edit silently opens an auth-free
 * path, a cross-tenant read, or {@code ?token=} on a non-GET route.
 *
 * <p>Tests the four overridden filter hooks directly (they are {@code
 * protected}, reachable from this package): the 8 bypass patterns of {@link
 * BrainAccessFilter#shouldRequireAuthentication}, the query-token gate, the
 * accepted token types, and the tenant-mismatch / SCRIPT_RUN claim check.
 * Each allowlist entry is paired with near-miss negatives (typo prefix, extra
 * path segment, wrong verb) that must keep requiring authentication.
 */
class BrainAccessFilterTest {

    private ScriptRunAuthService scriptRunAuthService;
    private BrainAccessFilter filter;

    @BeforeEach
    void setUp() {
        JwtService jwtService = mock(JwtService.class);
        scriptRunAuthService = mock(ScriptRunAuthService.class);
        filter = new BrainAccessFilter(jwtService, scriptRunAuthService);
    }

    // ──────────────── shouldRequireAuthentication: bypass allowlist ────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "/actuator/health",
        "/actuator/prometheus",
        "/internal/document/changed",
        "/face/addons",
        "/brain/acme/access/bob",       // token mint — client has no token yet
        "/brain/acme/access/bob/",      // trailing slash tolerated
        "/brain/acme/logout",
        "/brain/acme/logout/",
        "/brain/acme/event/proj/deploy", // external event trigger (own bearer check)
        "/brain/acme/office/download/file1",
        "/brain/acme/office/callback/file1",
        "/brain/acme/webdav",           // WebDAV Basic-Auth handled by milton
        "/brain/acme/webdav/folder/note.md",
    })
    void allowlistedPaths_doNotRequireBearerAuth(String uri) {
        assertThat(filter.shouldRequireAuthentication(uri, "GET")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/brain/acme/sessions",              // ordinary API path
        "/brain/acme/documents/doc1/content", // content route requires auth (query-token is a separate gate)
        "/brain/acme/access/bob/extra",       // mint + extra segment → not the mint shape
        "/brain/acme/event/proj",             // event trigger missing the event name
        "/brain/acme/event/proj/deploy/extra", // event trigger with an extra segment
        "/brain/acme/office/download",        // office callback missing the file segment
        "/brain/acme/office/delete/file1",    // office verb not in (download|callback)
        "/brain/acme/webdavx",                // prefix typo must NOT open webdav bypass
        "/brain/acme/logoutx",                // typo
        "/actuatorx/foo",                     // not the /actuator/ prefix
        "/internalx/foo",                     // not the /internal/ prefix
        "/facex/foo",                         // not the /face/ prefix
    })
    void nonAllowlistedPaths_requireBearerAuth(String uri) {
        assertThat(filter.shouldRequireAuthentication(uri, "GET")).isTrue();
    }

    // ──────────────── allowsQueryToken: GET-only, two routes ────────────────

    @Test
    void queryToken_allowedForGetContentAndWsUpgrade() {
        assertThat(filter.allowsQueryToken("/brain/acme/documents/doc1/content", "GET")).isTrue();
        assertThat(filter.allowsQueryToken("/brain/acme/ws", "GET")).isTrue();
        assertThat(filter.allowsQueryToken("/brain/acme/ws/", "GET")).isTrue();
    }

    @Test
    void queryToken_rejectedOnNonGetEvenForAllowedRoutes() {
        assertThat(filter.allowsQueryToken("/brain/acme/documents/doc1/content", "POST")).isFalse();
        assertThat(filter.allowsQueryToken("/brain/acme/ws", "POST")).isFalse();
    }

    @Test
    void queryToken_rejectedForOtherRoutesAndNearMisses() {
        assertThat(filter.allowsQueryToken("/brain/acme/sessions", "GET")).isFalse();
        assertThat(filter.allowsQueryToken("/brain/acme/documents/doc1/content/extra", "GET")).isFalse();
        assertThat(filter.allowsQueryToken("/brain/acme/documents/content", "GET")).isFalse();
    }

    // ──────────────── isTokenTypeAcceptable ────────────────

    @Test
    void acceptsAccessAndScriptRunTokens_rejectsRefresh() {
        assertThat(filter.isTokenTypeAcceptable(TokenType.ACCESS)).isTrue();
        assertThat(filter.isTokenTypeAcceptable(TokenType.SCRIPT_RUN)).isTrue();
        assertThat(filter.isTokenTypeAcceptable(TokenType.REFRESH)).isFalse();
    }

    // ──────────────── isClaimsAcceptable: tenant mismatch + SCRIPT_RUN ────────────────

    @Test
    void claims_acceptedWhenPathTenantMatchesJwtTenant() {
        VanceJwtClaims claims = access("bob", "acme");
        assertThat(filter.isClaimsAcceptable(claims, get("/brain/acme/sessions"))).isTrue();
    }

    @Test
    void claims_rejectedOnTenantMismatch() {
        VanceJwtClaims claims = access("bob", "evil");
        assertThat(filter.isClaimsAcceptable(claims, get("/brain/acme/sessions"))).isFalse();
    }

    @Test
    void claims_acceptedForNonTenantScopedPath() {
        // No /brain/{tenant} to cross-check — the JWT tenant is irrelevant here.
        VanceJwtClaims claims = access("bob", "whatever");
        assertThat(filter.isClaimsAcceptable(claims, get("/actuator/health"))).isTrue();
    }

    @Test
    void scriptRunClaims_delegateToScriptRunAuthService() {
        VanceJwtClaims claims = VanceJwtClaims.user("_script", "acme", null, null, TokenType.SCRIPT_RUN);
        HttpServletRequest req = get("/brain/acme/documents/doc1/content");

        when(scriptRunAuthService.isAcceptable(claims, req)).thenReturn(true);
        assertThat(filter.isClaimsAcceptable(claims, req)).isTrue();

        when(scriptRunAuthService.isAcceptable(claims, req)).thenReturn(false);
        assertThat(filter.isClaimsAcceptable(claims, req)).isFalse();
    }

    @Test
    void scriptRunClaims_stillRejectedOnTenantMismatchBeforeDelegation() {
        VanceJwtClaims claims = VanceJwtClaims.user("_script", "evil", null, null, TokenType.SCRIPT_RUN);
        assertThat(filter.isClaimsAcceptable(claims, get("/brain/acme/documents/doc1/content"))).isFalse();
    }

    // ──────────────── helpers ────────────────

    private static VanceJwtClaims access(String user, String tenant) {
        return VanceJwtClaims.user(user, tenant, null, null, TokenType.ACCESS);
    }

    private static HttpServletRequest get(String uri) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn(uri);
        return req;
    }
}
