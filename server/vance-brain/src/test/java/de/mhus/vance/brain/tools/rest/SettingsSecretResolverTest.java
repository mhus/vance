package de.mhus.vance.brain.tools.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.oauth.OAuthExpiredException;
import de.mhus.vance.brain.oauth.OAuthTokenRefresher;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the scope-aware {@link SettingsSecretResolver}.
 * Cascade (default), {@code tenant:}, {@code project:}, {@code user:}
 * + OAuth-access-token routing through {@link OAuthTokenRefresher}.
 */
class SettingsSecretResolverTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "instant-hole";
    private static final String USER = "wile.coyote";
    private static final String PROCESS = "p-1";

    private SettingService settings;
    private OAuthTokenRefresher refresher;
    private SettingsSecretResolver resolver;

    @BeforeEach
    void setUp() {
        settings = mock(SettingService.class);
        refresher = mock(OAuthTokenRefresher.class);
        resolver = new SettingsSecretResolver(settings, refresher);
    }

    // ─────── No-op paths ───────

    @Test
    void null_and_empty_input_pass_through() {
        ToolInvocationContext ctx = ctx();
        assertThat(resolver.resolve(null, ctx)).isNull();
        assertThat(resolver.resolve("", ctx)).isEmpty();
    }

    @Test
    void input_without_placeholders_passes_through_unchanged() {
        ToolInvocationContext ctx = ctx();
        String input = "Bearer fixed-token; X-Trace: 42";

        assertThat(resolver.resolve(input, ctx)).isEqualTo(input);
        verify(settings, never()).getDecryptedPasswordCascade(any(), any(), any(), any());
    }

    // ─────── Cascade (default scope) ───────

    @Test
    void cascade_scope_is_the_default() {
        when(settings.getDecryptedPasswordCascade(TENANT, PROJECT, PROCESS, "api.key"))
                .thenReturn("super-secret");

        String out = resolver.resolve("Bearer {{secret:api.key}}", ctx());

        assertThat(out).isEqualTo("Bearer super-secret");
    }

    @Test
    void cascade_unresolved_substitutes_empty_with_warn() {
        when(settings.getDecryptedPasswordCascade(any(), any(), any(), any())).thenReturn(null);

        String out = resolver.resolve("X-Key: {{secret:missing.key}}", ctx());

        assertThat(out).isEqualTo("X-Key: ");
    }

    // ─────── Explicit scopes ───────

    @Test
    void tenant_scope_routes_to_tenant_project() {
        when(settings.getDecryptedPassword(TENANT, SettingService.SCOPE_PROJECT,
                "_tenant", "vance.api.key"))
                .thenReturn("tenant-secret");

        String out = resolver.resolve("Bearer {{secret:tenant:vance.api.key}}", ctx());

        assertThat(out).isEqualTo("Bearer tenant-secret");
        verify(settings, never()).getDecryptedPasswordCascade(any(), any(), any(), any());
    }

    @Test
    void project_scope_routes_to_current_project() {
        when(settings.getDecryptedPassword(TENANT, SettingService.SCOPE_PROJECT,
                PROJECT, "db.password"))
                .thenReturn("project-secret");

        String out = resolver.resolve("Pass={{secret:project:db.password}}", ctx());

        assertThat(out).isEqualTo("Pass=project-secret");
    }

    @Test
    void user_scope_routes_to_user_settings() {
        when(settings.getDecryptedUserPassword(TENANT, USER, "github.pat"))
                .thenReturn("user-pat");

        String out = resolver.resolve("Auth: {{secret:user:github.pat}}", ctx());

        assertThat(out).isEqualTo("Auth: user-pat");
        verify(refresher, never()).resolveAccessToken(any(), any(), any());
    }

    @Test
    void user_scope_without_user_id_yields_empty_with_warn() {
        ToolInvocationContext ctxNoUser = new ToolInvocationContext(
                TENANT, PROJECT, "s-1", PROCESS, /*userId*/ null);

        String out = resolver.resolve("Auth: {{secret:user:github.pat}}", ctxNoUser);

        assertThat(out).isEqualTo("Auth: ");
        verify(settings, never()).getDecryptedUserPassword(any(), any(), any());
    }

    @Test
    void project_scope_without_project_id_yields_empty() {
        ToolInvocationContext ctxNoProject = new ToolInvocationContext(
                TENANT, /*projectId*/ null, "s-1", PROCESS, USER);

        String out = resolver.resolve("X: {{secret:project:db.password}}", ctxNoProject);

        assertThat(out).isEqualTo("X: ");
    }

    // ─────── OAuth access-token routing ───────

    @Test
    void user_scope_oauth_access_token_routes_through_refresher() {
        when(refresher.resolveAccessToken(TENANT, USER, "slack"))
                .thenReturn("fresh-slack-token");

        String out = resolver.resolve(
                "Authorization: Bearer {{secret:user:oauth.slack.access_token}}", ctx());

        assertThat(out).isEqualTo("Authorization: Bearer fresh-slack-token");
        verify(settings, never()).getDecryptedUserPassword(any(), any(), any());
    }

    @Test
    void oauth_refresh_token_does_not_route_through_refresher() {
        // Only the access_token form triggers refresh — refresh_token /
        // expires_at / scopes / extra fall through to direct user-setting reads.
        when(settings.getDecryptedUserPassword(TENANT, USER, "oauth.slack.refresh_token"))
                .thenReturn("stored-refresh");

        String out = resolver.resolve("{{secret:user:oauth.slack.refresh_token}}", ctx());

        assertThat(out).isEqualTo("stored-refresh");
        verify(refresher, never()).resolveAccessToken(any(), any(), any());
    }

    @Test
    void oauth_expired_exception_propagates_to_caller() {
        when(refresher.resolveAccessToken(TENANT, USER, "slack"))
                .thenThrow(new OAuthExpiredException("slack", "user must reconnect"));

        assertThatThrownBy(() -> resolver.resolve(
                "Bearer {{secret:user:oauth.slack.access_token}}", ctx()))
                .isInstanceOf(OAuthExpiredException.class)
                .extracting("providerId").isEqualTo("slack");
    }

    @Test
    void oauth_access_token_without_user_id_yields_empty_without_calling_refresher() {
        ToolInvocationContext ctxNoUser = new ToolInvocationContext(
                TENANT, PROJECT, "s-1", PROCESS, null);

        String out = resolver.resolve(
                "Bearer {{secret:user:oauth.slack.access_token}}", ctxNoUser);

        assertThat(out).isEqualTo("Bearer ");
        verify(refresher, never()).resolveAccessToken(any(), any(), any());
    }

    // ─────── Multiple placeholders ───────

    @Test
    void multiple_placeholders_in_one_string_all_resolve() {
        when(settings.getDecryptedPasswordCascade(TENANT, PROJECT, PROCESS, "a"))
                .thenReturn("A-VAL");
        when(settings.getDecryptedPassword(TENANT, SettingService.SCOPE_PROJECT,
                "_tenant", "b")).thenReturn("B-VAL");
        when(refresher.resolveAccessToken(TENANT, USER, "slack")).thenReturn("SLACK-VAL");

        String out = resolver.resolve(
                "X={{secret:a}}&Y={{secret:tenant:b}}&Z={{secret:user:oauth.slack.access_token}}",
                ctx());

        assertThat(out).isEqualTo("X=A-VAL&Y=B-VAL&Z=SLACK-VAL");
    }

    @Test
    void unknown_scope_prefix_treats_whole_body_as_key() {
        // "foo:bar" — "foo" isn't a recognised scope; the resolver
        // forwards the whole body as the cascade key. Defensive against
        // future keys that include a colon and shouldn't be misparsed.
        when(settings.getDecryptedPasswordCascade(TENANT, PROJECT, PROCESS, "foo:bar"))
                .thenReturn("ok");

        String out = resolver.resolve("{{secret:foo:bar}}", ctx());

        assertThat(out).isEqualTo("ok");
    }

    // ─────── Missing context ───────

    @Test
    void no_tenant_id_yields_empty_substitution() {
        ToolInvocationContext ctxNoTenant = new ToolInvocationContext(
                /*tenantId*/ "", PROJECT, "s-1", PROCESS, USER);

        String out = resolver.resolve("X={{secret:api.key}}", ctxNoTenant);

        assertThat(out).isEqualTo("X=");
        verify(settings, never()).getDecryptedPasswordCascade(any(), any(), any(), any());
    }

    // ─────── Helpers ───────

    private static ToolInvocationContext ctx() {
        return new ToolInvocationContext(TENANT, PROJECT, "s-1", PROCESS, USER);
    }
}
