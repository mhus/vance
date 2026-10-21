package de.mhus.vance.brain.access;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.jwt.VanceJwtClaims;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The structural half of every narrowed credential: profile existence,
 * project-pin coherence, surface match, and the documented self-revoke
 * carve-out. Lives here rather than inside
 * {@code IntegrationTokenAuthServiceTest} because the carve-out has its own
 * invariants (only INTEGRATION, only DELETE, only the literal path) that
 * are easier to read when pinned in one place.
 */
class NarrowedCredentialCheckTest {

    private record Profile(String id, boolean requiresProject) implements IntegrationScopeProfile {
        @Override
        public String label() {
            return id;
        }

        @Override
        public List<IntegrationSurface> surfaces() {
            return List.of(IntegrationSurface.of("POST", "/addon/" + id + "/entry"));
        }
    }

    private NarrowedCredentialCheck check;

    @BeforeEach
    void setUp() {
        check = new NarrowedCredentialCheck(
                new IntegrationScopeRegistry(List.of(new Profile("capture", true), new Profile("admin", false))));
    }

    private static VanceJwtClaims integration(List<String> profiles, String projectId, String jti) {
        return VanceJwtClaims.integration(
                "alice", "acme", Instant.now(), Instant.now().plusSeconds(3600), jti, profiles, projectId);
    }

    private static MockHttpServletRequest req(String method, String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRequestURI(uri);
        return req;
    }

    // ─── self-revoke carve-out ──────────────────────────────────────

    @Test
    void admits_integrationTokenAtSelfRevokeEndpoint() {
        // The integration token carries a project-pinned profile but no project
        // pin — would normally be rejected. The /current URL is the exception:
        // the URL itself is the authority.
        assertThat(check.admits(
                        "INTEGRATION",
                        integration(List.of("capture"), null, "tok-1"),
                        req("DELETE", "/brain/acme/integration-tokens/current")))
                .isTrue();
    }

    @Test
    void rejects_nonIntegrationKind_atSelfRevokeEndpoint() {
        // ACCESS / OAUTH tokens do not get to use the carve-out — they revoke
        // themselves through the session they belong to. 400 in the controller,
        // 403 in the structural check is fine: the carve-out is only ever
        // triggered by the URL itself, so a non-INTEGRATION token reaching
        // this URL is already a caller bug.
        VanceJwtClaims oauth = new VanceJwtClaims(
                "alice",
                "acme",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                de.mhus.vance.shared.jwt.TokenType.OAUTH,
                null,
                null,
                null,
                List.of(),
                "tok-1",
                null,
                null,
                null,
                null);
        assertThat(check.admits("OAUTH", oauth, req("DELETE", "/brain/acme/integration-tokens/current")))
                .isFalse();
    }

    @Test
    void rejects_getMethod_atSelfRevokeEndpoint() {
        // Self-revoke is DELETE only. A GET here would be a different endpoint
        // entirely; the carve-out must not admit it.
        assertThat(check.admits(
                        "INTEGRATION",
                        integration(List.of("capture"), "p1", "tok-1"),
                        req("GET", "/brain/acme/integration-tokens/current")))
                .isFalse();
    }

    @Test
    void rejects_postMethod_atSelfRevokeEndpoint() {
        // Same idea for POST — a tool that POSTs to /current is wrong, and
        // must not be admitted by the carve-out.
        assertThat(check.admits(
                        "INTEGRATION",
                        integration(List.of("capture"), "p1", "tok-1"),
                        req("POST", "/brain/acme/integration-tokens/current")))
                .isFalse();
    }

    @Test
    void rejects_similarPath_atDifferentMethod() {
        // The carve-out is DELETE on the literal /integration-tokens/current.
        // A slightly different URL must fall through to the regular profile
        // match — which fails here because the carried profile does not cover
        // this path.
        assertThat(check.admits(
                        "INTEGRATION",
                        integration(List.of("capture"), "p1", "tok-1"),
                        req("DELETE", "/brain/acme/integration-tokens/current/extended")))
                .isFalse();
    }

    @Test
    void rejects_similarPath_atSelfRevokeMethod() {
        // /integration-tokens/{tokenId} — the regular revoke endpoint, taken
        // by the existing @DeleteMapping with the same HTTP verb. The
        // carve-out is for the literal /current only.
        assertThat(check.admits(
                        "INTEGRATION",
                        integration(List.of("capture"), "p1", "tok-1"),
                        req("DELETE", "/brain/acme/integration-tokens/some-jti")))
                .as("the regular revoke path keeps its existing semantics: " + "profile must cover it")
                .isFalse();
    }

    // ─── structural checks (regression pins) ────────────────────────

    @Test
    void admits_aProjectPinnedProfile_coveringThePath() {
        HttpServletRequest request = req("POST", "/brain/acme/addon/capture/entry");
        assertThat(check.admits("INTEGRATION", integration(List.of("capture"), "p1", "tok-1"), request))
                .isTrue();
    }

    @Test
    void rejects_missingProjectPin_forPinnedProfile() {
        // Pinned profile + no project → refuse. The mint also refuses this
        // combination, but the structural check is the second line of defence.
        HttpServletRequest request = req("POST", "/brain/acme/addon/capture/entry");
        assertThat(check.admits("INTEGRATION", integration(List.of("capture"), null, "tok-1"), request))
                .isFalse();
    }
}
