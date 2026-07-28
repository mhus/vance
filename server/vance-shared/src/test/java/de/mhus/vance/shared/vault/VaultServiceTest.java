package de.mhus.vance.shared.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VaultServiceTest {

    private static final String TENANT = "t1";
    private static final String USER = "u1";
    private static final String PROJECT = "p1";
    private static final String USER_LAYER = HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + USER;
    private static final VaultScope SCOPE = new VaultScope(TENANT, USER, PROJECT);

    private SettingService settingService;

    @BeforeEach
    void setUp() {
        settingService = mock(SettingService.class);
    }

    /** Stubs a non-secret {@code vault.*} setting on one scope layer. */
    private void setting(String layerRef, String key, @Nullable String value) {
        when(settingService.getStringValue(
                eq(TENANT), eq(SettingService.SCOPE_PROJECT), eq(layerRef), eq(key)))
                .thenReturn(value);
    }

    /** Stubs the decrypted {@code vault.clientSecret} setting on one scope layer. */
    private void secretSetting(String layerRef, String key, @Nullable String value) {
        when(settingService.getDecryptedPassword(
                eq(TENANT), eq(SettingService.SCOPE_PROJECT), eq(layerRef), eq(key)))
                .thenReturn(value);
    }

    private VaultService serviceWith(VaultProvider... providers) {
        return new VaultService(settingService, List.of(providers));
    }

    @Test
    void readSecret_boundVault_delegatesToMatchingProviderAndReturnsValue() {
        setting(PROJECT, "vault.type", "infisical");
        setting(PROJECT, "vault.baseUrl", "https://vault.example.tld");
        RecordingProvider infisical = new RecordingProvider("infisical");
        infisical.store.put("jira-token", "s3cr3t");

        String value = serviceWith(infisical).readSecret(SCOPE, "jira-token");

        assertThat(value).isEqualTo("s3cr3t");
    }

    @Test
    void readSecret_boundVault_handsProviderTheResolvedBinding() {
        setting(PROJECT, "vault.type", "infisical");
        setting(PROJECT, "vault.baseUrl", "https://vault.example.tld");
        setting(PROJECT, "vault.project", "proj-123");
        setting(PROJECT, "vault.environment", "prod");
        setting(PROJECT, "vault.path", "/api");
        setting(PROJECT, "vault.clientId", "client-abc");
        secretSetting(PROJECT, "vault.clientSecret", "client-secret-xyz");
        RecordingProvider infisical = new RecordingProvider("infisical");

        serviceWith(infisical).readSecret(SCOPE, "any-key");

        VaultBinding b = infisical.lastBinding;
        assertThat(b).isNotNull();
        assertThat(b.type()).isEqualTo("infisical");
        assertThat(b.baseUrl()).isEqualTo("https://vault.example.tld");
        assertThat(b.config()).containsEntry("project", "proj-123")
                .containsEntry("environment", "prod")
                .containsEntry("path", "/api")
                .containsEntry("clientId", "client-abc");
        assertThat(b.secret()).isEqualTo("client-secret-xyz");
    }

    @Test
    void resolveBinding_innerLayerWithoutType_takesEntireBindingFromProjectLayer() {
        // Full binding on the project layer …
        setting(PROJECT, "vault.type", "infisical");
        setting(PROJECT, "vault.baseUrl", "https://project.vault");
        setting(PROJECT, "vault.clientId", "project-client");
        secretSetting(PROJECT, "vault.clientSecret", "project-secret");
        // … while the user layer carries ONLY a client-secret and no vault.type.
        secretSetting(USER_LAYER, "vault.clientSecret", "stray-user-secret");
        RecordingProvider infisical = new RecordingProvider("infisical");

        serviceWith(infisical).readSecret(SCOPE, "k");

        // The binding must not straddle layers: the user's stray secret is ignored
        // because the user layer has no vault.type — the whole binding is the project's.
        assertThat(infisical.lastBinding.secret()).isEqualTo("project-secret");
        assertThat(infisical.lastBinding.config()).containsEntry("clientId", "project-client");
    }

    @Test
    void resolveBinding_userLayerWithType_winsAsWholeBinding() {
        setting(USER_LAYER, "vault.type", "infisical");
        setting(USER_LAYER, "vault.baseUrl", "https://user.vault");
        secretSetting(USER_LAYER, "vault.clientSecret", "user-secret");
        // A different project binding must be fully shadowed, not merged.
        setting(PROJECT, "vault.type", "infisical");
        setting(PROJECT, "vault.baseUrl", "https://project.vault");
        secretSetting(PROJECT, "vault.clientSecret", "project-secret");
        RecordingProvider infisical = new RecordingProvider("infisical");

        serviceWith(infisical).readSecret(SCOPE, "k");

        assertThat(infisical.lastBinding.baseUrl()).isEqualTo("https://user.vault");
        assertThat(infisical.lastBinding.secret()).isEqualTo("user-secret");
    }

    @Test
    void readSecret_secretAbsentInVault_returnsNull() {
        setting(PROJECT, "vault.type", "infisical");
        setting(PROJECT, "vault.baseUrl", "https://vault.example.tld");
        RecordingProvider infisical = new RecordingProvider("infisical"); // empty store

        String value = serviceWith(infisical).readSecret(SCOPE, "missing");

        assertThat(value).isNull();
    }

    @Test
    void readSecret_noVaultBound_throwsVaultException() {
        // vault.type unset on every layer → no binding layer found
        RecordingProvider infisical = new RecordingProvider("infisical");

        assertThatThrownBy(() -> serviceWith(infisical).readSecret(SCOPE, "k"))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("No vault bound");
    }

    @Test
    void readSecret_noProviderForConfiguredType_throwsVaultException() {
        setting(PROJECT, "vault.type", "hashicorp");
        setting(PROJECT, "vault.baseUrl", "https://vault.example.tld");

        assertThatThrownBy(() -> serviceWith(new RecordingProvider("infisical")).readSecret(SCOPE, "k"))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("No vault provider registered for type 'hashicorp'");
    }

    @Test
    void readSecret_missingBaseUrl_throwsVaultException() {
        setting(PROJECT, "vault.type", "infisical");
        // vault.baseUrl unset

        assertThatThrownBy(() -> serviceWith(new RecordingProvider("infisical")).readSecret(SCOPE, "k"))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("vault.baseUrl");
    }

    @Test
    void readSecret_blankKey_throwsVaultException() {
        assertThatThrownBy(() -> serviceWith(new RecordingProvider("infisical")).readSecret(SCOPE, "  "))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void selectProvider_twoProvidersSameType_throwsVaultException() {
        setting(PROJECT, "vault.type", "infisical");
        setting(PROJECT, "vault.baseUrl", "https://vault.example.tld");

        assertThatThrownBy(() -> serviceWith(
                new RecordingProvider("infisical"), new RecordingProvider("infisical"))
                .readSecret(SCOPE, "k"))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("Multiple vault providers");
    }

    @Test
    void isConfigured_reflectsTypeSetting() {
        VaultService svc = serviceWith(new RecordingProvider("infisical"));
        assertThat(svc.isConfigured(SCOPE)).isFalse();

        setting(PROJECT, "vault.type", "infisical");
        assertThat(svc.isConfigured(SCOPE)).isTrue();
    }

    @Test
    void writeSecret_defaultImplementation_throwsUnsupported() {
        VaultProvider readOnly = new VaultProvider() {
            @Override
            public String type() {
                return "readonly";
            }

            @Override
            public @Nullable String readSecret(VaultBinding binding, String key) {
                return null;
            }
        };

        assertThatThrownBy(() -> readOnly.writeSecret(
                new VaultBinding("readonly", "https://x", Map.of(), null), "k", "v"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("readonly");
    }

    /** In-memory {@link VaultProvider} that records the last binding it saw. */
    private static final class RecordingProvider implements VaultProvider {
        private final String type;
        private final Map<String, String> store = new HashMap<>();
        private @Nullable VaultBinding lastBinding;

        RecordingProvider(String type) {
            this.type = type;
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public @Nullable String readSecret(VaultBinding binding, String key) {
            this.lastBinding = binding;
            return store.get(key);
        }
    }
}
