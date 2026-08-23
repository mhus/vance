package de.mhus.vance.brain.tools.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.brain.oauth.OAuthExpiredException;
import de.mhus.vance.brain.oauth.OAuthTokenRefresher;
import de.mhus.vance.shared.settings.SecretAccessDeniedException;
import de.mhus.vance.shared.settings.SecretReferenceKeyPolicy;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.vault.VaultException;
import de.mhus.vance.shared.vault.VaultScope;
import de.mhus.vance.shared.vault.VaultService;
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
    private VaultService vault;
    private SettingsSecretResolver resolver;

    @BeforeEach
    void setUp() {
        settings = mock(SettingService.class);
        refresher = mock(OAuthTokenRefresher.class);
        vault = mock(VaultService.class);
        resolver = new SettingsSecretResolver(settings, refresher, vault,
                new SecretReferenceKeyPolicy("ai.provider.*,vault.*"));
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
        verify(settings, never()).getReferenceSecretCascade(any(), any(), any(), any());
    }

    // ─────── Cascade (default scope) ───────

    @Test
    void cascade_scope_is_the_default() {
        when(settings.getReferenceSecretCascade(TENANT, PROJECT, PROCESS, "api.key"))
                .thenReturn("super-secret");

        String out = resolver.resolve("Bearer {{secret:api.key}}", ctx());

        assertThat(out).isEqualTo("Bearer super-secret");
    }

    @Test
    void cascade_unresolved_substitutes_empty_with_warn() {
        when(settings.getReferenceSecretCascade(any(), any(), any(), any())).thenReturn(null);

        String out = resolver.resolve("X-Key: {{secret:missing.key}}", ctx());

        assertThat(out).isEqualTo("X-Key: ");
    }

    // ─────── Explicit scopes ───────

    @Test
    void tenant_scope_routes_to_tenant_project() {
        when(settings.getReferenceSecret(TENANT, SettingService.SCOPE_PROJECT,
                "_tenant", "vance.api.key"))
                .thenReturn("tenant-secret");

        String out = resolver.resolve("Bearer {{secret:tenant:vance.api.key}}", ctx());

        assertThat(out).isEqualTo("Bearer tenant-secret");
        verify(settings, never()).getReferenceSecretCascade(any(), any(), any(), any());
    }

    @Test
    void project_scope_routes_to_current_project() {
        when(settings.getReferenceSecret(TENANT, SettingService.SCOPE_PROJECT,
                PROJECT, "db.password"))
                .thenReturn("project-secret");

        String out = resolver.resolve("Pass={{secret:project:db.password}}", ctx());

        assertThat(out).isEqualTo("Pass=project-secret");
    }

    @Test
    void user_scope_routes_to_user_settings() {
        when(settings.getReferenceUserSecret(TENANT, USER, "github.pat"))
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
        verify(settings, never()).getReferenceUserSecret(any(), any(), any());
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
        verify(settings, never()).getReferenceUserSecret(any(), any(), any());
    }

    @Test
    void oauth_refresh_token_does_not_route_through_refresher() {
        // Only the access_token form triggers refresh — refresh_token /
        // expires_at / scopes / extra fall through to direct user-setting reads.
        when(settings.getReferenceUserSecret(TENANT, USER, "oauth.slack.refresh_token"))
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

    // ─────── Vault scope ───────

    @Test
    void vault_scope_routes_to_vault_service() {
        when(vault.readSecret(new VaultScope(TENANT, USER, PROJECT), "jira-token"))
                .thenReturn("vault-secret");

        String out = resolver.resolve("Bearer {{secret:vault:jira-token}}", ctx());

        assertThat(out).isEqualTo("Bearer vault-secret");
        verify(settings, never()).getReferenceSecretCascade(any(), any(), any(), any());
    }

    @Test
    void vault_scope_absent_secret_substitutes_empty() {
        when(vault.readSecret(any(), eq("missing"))).thenReturn(null);

        String out = resolver.resolve("X: {{secret:vault:missing}}", ctx());

        assertThat(out).isEqualTo("X: ");
    }

    @Test
    void vault_exception_fails_closed_to_empty() {
        // A misconfigured / unreachable vault must not propagate — it fails
        // closed like any unresolved secret so the tool call escalates to 401.
        when(vault.readSecret(any(), eq("db.pw")))
                .thenThrow(new VaultException("No vault bound at scope"));

        String out = resolver.resolve("Pass={{secret:vault:db.pw}}", ctx());

        assertThat(out).isEqualTo("Pass=");
    }

    // ─────── Multiple placeholders ───────

    @Test
    void multiple_placeholders_in_one_string_all_resolve() {
        when(settings.getReferenceSecretCascade(TENANT, PROJECT, PROCESS, "a"))
                .thenReturn("A-VAL");
        when(settings.getReferenceSecret(TENANT, SettingService.SCOPE_PROJECT,
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
        when(settings.getReferenceSecretCascade(TENANT, PROJECT, PROCESS, "foo:bar"))
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
        verify(settings, never()).getReferenceSecretCascade(any(), any(), any(), any());
    }

    // ─────── PASSWORD-typed targets are denied, not silently empty ───────
    //
    // The reference-readability gate lives in SettingService.getReferenceSecret*;
    // what this class owns is that the denial *propagates* instead of being
    // folded into the fail-closed-to-empty rule for genuinely absent secrets.
    // Otherwise a re-typing mistake would look like a missing setting and
    // surface as an opaque downstream 401.

    // These use ordinary credential keys on purpose. A reserved key
    // (ai.provider.*, vault.*) is refused by SecretReferenceKeyPolicy before
    // any lookup happens, so it would exercise the name guard rather than the
    // type guard these tests are about.

    @Test
    void cascade_scope_denial_propagates_instead_of_substituting_empty() {
        when(settings.getReferenceSecretCascade(TENANT, PROJECT, PROCESS, "smtp.password"))
                .thenThrow(new SecretAccessDeniedException(
                        "smtp.password", SettingType.PASSWORD));

        assertThatThrownBy(() -> resolver.resolve(
                "Bearer {{secret:smtp.password}}", ctx()))
                .isInstanceOf(SecretAccessDeniedException.class)
                .hasMessageContaining("HIDDEN");
    }

    @Test
    void project_scope_denial_propagates() {
        when(settings.getReferenceSecret(TENANT, SettingService.SCOPE_PROJECT, PROJECT, "db.password"))
                .thenThrow(new SecretAccessDeniedException("db.password", SettingType.PASSWORD));

        assertThatThrownBy(() -> resolver.resolve("Pass={{secret:project:db.password}}", ctx()))
                .isInstanceOf(SecretAccessDeniedException.class);
    }

    @Test
    void tenant_scope_denial_propagates() {
        when(settings.getReferenceSecret(TENANT, SettingService.SCOPE_PROJECT,
                "_tenant", "credentials.jira.api_token"))
                .thenThrow(new SecretAccessDeniedException(
                        "credentials.jira.api_token", SettingType.PASSWORD));

        assertThatThrownBy(() ->
                resolver.resolve("{{secret:tenant:credentials.jira.api_token}}", ctx()))
                .isInstanceOf(SecretAccessDeniedException.class)
                .extracting("key").isEqualTo("credentials.jira.api_token");
    }

    @Test
    void user_scope_denial_propagates() {
        when(settings.getReferenceUserSecret(TENANT, USER, "github.pat"))
                .thenThrow(new SecretAccessDeniedException("github.pat", SettingType.PASSWORD));

        assertThatThrownBy(() -> resolver.resolve("Auth: {{secret:user:github.pat}}", ctx()))
                .isInstanceOf(SecretAccessDeniedException.class);
    }

    @Test
    void denial_in_one_reference_aborts_the_whole_input() {
        // No partial substitution: a template with a good and a denied reference
        // must not emit the resolved half of an auth header.
        when(settings.getReferenceSecretCascade(TENANT, PROJECT, PROCESS, "ok.key"))
                .thenReturn("fine");
        when(settings.getReferenceSecretCascade(TENANT, PROJECT, PROCESS, "denied.key"))
                .thenThrow(new SecretAccessDeniedException("denied.key", SettingType.PASSWORD));

        assertThatThrownBy(() -> resolver.resolve(
                "a={{secret:ok.key}}&b={{secret:denied.key}}", ctx()))
                .isInstanceOf(SecretAccessDeniedException.class);
    }

    @Test
    void vault_references_are_unaffected_by_the_setting_type_gate() {
        // This resolver applies no type gate to vault: — whether one applies is
        // the provider's business (the settings-backed one reads through the
        // HIDDEN-only path, an external manager has no setting types at all).
        // vault: therefore stays the agent-facing secret channel.
        when(vault.readSecret(new VaultScope(TENANT, USER, PROJECT), "deploy-token"))
                .thenReturn("from-vault");

        assertThat(resolver.resolve("Bearer {{secret:vault:deploy-token}}", ctx()))
                .isEqualTo("Bearer from-vault");
        verify(settings, never()).getReferenceSecretCascade(any(), any(), any(), any());
        verify(settings, never()).getReferenceSecret(any(), any(), any(), any());
    }

    // ─────── Helpers ───────

    // ─────── Reserved keys are unreachable through any reference ───────

    @Test
    void reservedKey_isRefusedOnTheConnectorPath() {
        // The connector path reads PASSWORD by design, so the type is no
        // longer what keeps a reference away from the provider key. A REST
        // tool document declares its target URL next to its headers — this
        // reference would ship the key wherever that document points.
        assertThatThrownBy(() -> resolver.resolveForConnector(
                "Bearer {{secret:project:ai.provider.openai.apiKey}}", ctx()))
                .isInstanceOf(SecretAccessDeniedException.class)
                .hasMessageContaining("ai.provider.openai.apiKey");

        verify(settings, never()).getDecryptedPassword(any(), any(), any(), any());
    }

    @Test
    void reservedKey_isRefusedOnTheRestrictedPathToo() {
        // Defence in depth: HIDDEN-only would already stop a correctly typed
        // provider key, but a hand-typed one must not become readable just
        // because someone classified it wrongly.
        assertThatThrownBy(() -> resolver.resolve("{{secret:vault.clientSecret}}", ctx()))
                .isInstanceOf(SecretAccessDeniedException.class);

        verify(settings, never()).getReferenceSecretCascade(any(), any(), any(), any());
    }

    @Test
    void reservedKey_isRefusedInEveryScope() {
        for (String ref : new String[] {
                "{{secret:ai.provider.openai.apiKey}}",
                "{{secret:tenant:ai.provider.openai.apiKey}}",
                "{{secret:user:ai.provider.openai.apiKey}}",
                "{{secret:project:vault.clientId}}"}) {
            assertThatThrownBy(() -> resolver.resolveForConnector(ref, ctx()))
                    .as(ref)
                    .isInstanceOf(SecretAccessDeniedException.class);
        }
    }

    @Test
    void vaultScope_isNotMatchedAgainstSettingPatterns() {
        // `vault:` names an entry in the vault's own namespace, not a setting.
        // An Infisical secret that merely spells like the reserved prefix must
        // still resolve — the deny-list governs setting keys, and this layer
        // cannot tell whose namespace it is looking at. The provider can, and
        // the settings-backed one applies the very same policy itself
        // (SettingsVaultProviderTest), so the barrier is not lost — it just
        // sits where the answer is knowable.
        when(vault.readSecret(any(VaultScope.class), eq("vault.clientId")))
                .thenReturn("from-vault");

        assertThat(resolver.resolveForConnector("{{secret:vault:vault.clientId}}", ctx()))
                .isEqualTo("from-vault");
    }

    @Test
    void nameGuardRunsBeforeAnyLookup() {
        // The refusal must not depend on the setting existing, or on how it
        // is typed — a reserved key is unreachable even if nothing is stored
        // under it, so an agent cannot probe for one either.
        assertThatThrownBy(() -> resolver.resolve("{{secret:ai.provider.openai.apiKey}}", ctx()))
                .isInstanceOf(SecretAccessDeniedException.class)
                .extracting("key").isEqualTo("ai.provider.openai.apiKey");

        verify(settings, never()).getReferenceSecretCascade(any(), any(), any(), any());
        verify(settings, never()).getDecryptedPasswordCascade(any(), any(), any(), any());
    }

    @Test
    void ordinaryConnectorCredential_stillResolves() {
        when(settings.getDecryptedPassword(TENANT, "project", PROJECT, "smtp.password"))
                .thenReturn("hunter2");

        assertThat(resolver.resolveForConnector("{{secret:project:smtp.password}}", ctx()))
                .isEqualTo("hunter2");
    }

    private static ToolInvocationContext ctx() {
        return new ToolInvocationContext(TENANT, PROJECT, "s-1", PROCESS, USER);
    }
}
