package de.mhus.vance.shared.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.settings.SecretAccessDeniedException;
import de.mhus.vance.shared.settings.SecretReferenceKeyPolicy;
import de.mhus.vance.shared.settings.SettingService;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The settings-backed vault. Its whole risk is <em>which</em> read path it uses:
 * before it existed, {@code vault:} references never touched settings and so
 * bypassed the PASSWORD/HIDDEN gate legitimately. Now they do touch settings, and
 * reaching for {@code getDecryptedPassword} instead of the reference path would
 * make {@code {{secret:vault:ai.provider.default.apiKey}}} readable again.
 */
class SettingsVaultProviderTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "instant-hole";
    private static final String USER = "wile.coyote";
    private static final VaultScope SCOPE = new VaultScope(TENANT, USER, PROJECT);
    private static final VaultBinding BINDING =
            new VaultBinding(SettingsVaultProvider.TYPE, "", Map.of(), null);

    private final SettingService settingService = mock(SettingService.class);
    private final SettingsVaultProvider provider = new SettingsVaultProvider(
            settingService, new SecretReferenceKeyPolicy("ai.provider.*,vault.*"));

    @Test
    void it_registers_under_the_settings_type_and_needs_no_endpoint() {
        assertThat(provider.type()).isEqualTo("settings");
        assertThat(provider.requiresEndpoint()).isFalse();
    }

    // ─────── read ───────

    @Test
    void read_goes_through_the_reference_path_so_only_hidden_resolves() {
        when(settingService.getReferenceSecretCascade(TENANT, PROJECT, null, "jira-token"))
                .thenReturn("tok");

        assertThat(provider.readSecret(BINDING, SCOPE, "jira-token")).isEqualTo("tok");

        // The compiled-reader path must not be used here — it reads PASSWORD too.
        verify(settingService, never()).getDecryptedPassword(
                eq(TENANT), eq(SettingService.SCOPE_PROJECT), eq(PROJECT), eq("jira-token"));
        verify(settingService, never()).getDecryptedPasswordCascade(
                eq(TENANT), eq(PROJECT), eq(null), eq("jira-token"));
    }

    @Test
    void read_of_a_password_setting_propagates_the_denial() {
        when(settingService.getReferenceSecretCascade(TENANT, PROJECT, null, "ai.provider.default.apiKey"))
                .thenThrow(new SecretAccessDeniedException(
                        "ai.provider.default.apiKey", SettingType.PASSWORD));

        assertThatThrownBy(() ->
                provider.readSecret(BINDING, SCOPE, "ai.provider.default.apiKey"))
                .isInstanceOf(SecretAccessDeniedException.class);
    }

    @Test
    void read_of_a_reserved_key_is_refused_by_name_before_any_lookup() {
        // The second barrier, and the reason it has to sit *here*: the resolver
        // exempts the whole `vault:` scope from the key deny-list, on the
        // reasoning that a vault key names an entry in a foreign namespace. That
        // reasoning stopped covering the default installation the moment this
        // provider became the fallback — the key it gets *is* a setting key.
        // Without this, a provider key mis-typed as HIDDEN (the realistic
        // mis-pick in the admin UI) would be readable through
        // {{secret:vault:ai.provider.openai.apiKey}} while the identical
        // {{secret:project:…}} reference is refused.
        assertThatThrownBy(() ->
                provider.readSecret(BINDING, SCOPE, "ai.provider.openai.apiKey"))
                .isInstanceOf(SecretAccessDeniedException.class)
                .hasMessageContaining("ai.provider.openai.apiKey");

        verify(settingService, never()).getReferenceSecretCascade(
                eq(TENANT), eq(PROJECT), eq(null), eq("ai.provider.openai.apiKey"));
    }

    @Test
    void read_of_the_vault_binding_itself_is_refused() {
        // vault.* is on the same list: the binding credential must not be
        // resolvable through the vault it configures.
        assertThatThrownBy(() -> provider.readSecret(BINDING, SCOPE, "vault.clientSecret"))
                .isInstanceOf(SecretAccessDeniedException.class);
    }

    @Test
    void read_of_an_ordinary_key_is_untouched_by_the_deny_list() {
        when(settingService.getReferenceSecretCascade(TENANT, PROJECT, null, "kit.token.acme"))
                .thenReturn("tok");

        // kit.token.* is deliberately absent from the reference deny-list —
        // provisioning resolves exactly that key. The guard must not widen.
        assertThat(provider.readSecret(BINDING, SCOPE, "kit.token.acme")).isEqualTo("tok");
    }

    @Test
    void read_uses_the_project_cascade_and_skips_the_user_layer() {
        // A per-user setting must not shadow a project credential that a shared
        // tool document depends on — that is what {{secret:user:…}} is for.
        provider.readSecret(BINDING, SCOPE, "smtp.password");

        verify(settingService).getReferenceSecretCascade(TENANT, PROJECT, null, "smtp.password");
        verify(settingService, never()).getReferenceUserSecret(eq(TENANT), eq(USER), eq("smtp.password"));
    }

    @Test
    void read_without_a_project_still_resolves_against_the_tenant_layer() {
        VaultScope headless = new VaultScope(TENANT, null, null);
        when(settingService.getReferenceSecretCascade(TENANT, null, null, "k")).thenReturn("v");

        assertThat(provider.readSecret(BINDING, headless, "k")).isEqualTo("v");
    }

    // ─────── write ───────

    @Test
    void write_goes_through_the_agent_write_rules() {
        provider.writeSecret(BINDING, SCOPE, "deploy-token", "s3cr3t");

        // setAgentSecret carries W1 (no PASSWORD overwrite) and W3 (deny-list) —
        // the right gate, since the callers are LLM tools. HIDDEN because a vault
        // secret exists to be resolved by scripts and compose tasks.
        verify(settingService).setAgentSecret(
                TENANT, SettingService.SCOPE_PROJECT, PROJECT, "deploy-token", "s3cr3t",
                SettingType.HIDDEN);
    }

    @Test
    void write_without_a_project_is_refused_rather_than_redirected_to_the_tenant() {
        // Silently writing to _tenant would let a project-scoped caller create a
        // tenant-wide credential.
        VaultScope headless = new VaultScope(TENANT, null, null);

        assertThatThrownBy(() -> provider.writeSecret(BINDING, headless, "k", "v"))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("requires a project scope");
    }
}
