package de.mhus.vance.shared.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.settings.SettingService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
        return new VaultService(
                settingService, List.of(providers), new MetricService(new SimpleMeterRegistry()));
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
    void readSecret_noVaultBound_fallsBackToTheSettingsVault() {
        // vault.type unset on every layer → no binding layer found. That is not an
        // error: {{secret:vault:<key>}} has to work before anyone configures an
        // external manager, so the settings-backed vault takes over.
        RecordingProvider infisical = new RecordingProvider("infisical");
        RecordingProvider settings = new RecordingProvider(SettingsVaultProvider.TYPE);
        settings.store.put("k", "from-settings");

        assertThat(serviceWith(infisical, settings).readSecret(SCOPE, "k"))
                .isEqualTo("from-settings");
        assertThat(infisical.lastBinding).as("external provider untouched").isNull();
        assertThat(settings.lastBinding).isNotNull()
                .extracting(VaultBinding::type).isEqualTo(SettingsVaultProvider.TYPE);
    }

    @Test
    void readSecret_boundVaultWinsOverTheSettingsFallback() {
        setting(PROJECT, "vault.type", "infisical");
        setting(PROJECT, "vault.baseUrl", "https://vault.example.tld");
        RecordingProvider infisical = new RecordingProvider("infisical");
        infisical.store.put("k", "from-infisical");
        RecordingProvider settings = new RecordingProvider(SettingsVaultProvider.TYPE);
        settings.store.put("k", "from-settings");

        assertThat(serviceWith(infisical, settings).readSecret(SCOPE, "k"))
                .isEqualTo("from-infisical");
        assertThat(settings.lastBinding).as("fallback not consulted").isNull();
    }

    @Test
    void readSecret_settingsTypeSetExplicitly_needsNoBaseUrl() {
        // requiresEndpoint()=false — demanding a URL for a locally resolving vault
        // would be a made-up requirement.
        setting(PROJECT, "vault.type", SettingsVaultProvider.TYPE);
        RecordingProvider settings = new RecordingProvider(SettingsVaultProvider.TYPE) {
            @Override
            public boolean requiresEndpoint() {
                return false;
            }
        };
        settings.store.put("k", "v");

        assertThat(serviceWith(settings).readSecret(SCOPE, "k")).isEqualTo("v");
    }

    @Test
    void readSecret_remoteTypeWithoutBaseUrl_stillThrows() {
        setting(PROJECT, "vault.type", "infisical");

        assertThatThrownBy(() -> serviceWith(new RecordingProvider("infisical")).readSecret(SCOPE, "k"))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("vault.baseUrl");
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
            public @Nullable String readSecret(VaultBinding binding, VaultScope scope, String key) {
                return null;
            }
        };

        assertThatThrownBy(() -> readOnly.writeSecret(
                new VaultBinding("readonly", "https://x", Map.of(), null), SCOPE, "k", "v"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("readonly");
    }

    // ─────── write / generate ───────

    @Test
    void writeSecret_boundVault_delegatesToProvider() {
        setting(PROJECT, "vault.type", "infisical");
        setting(PROJECT, "vault.baseUrl", "https://vault.example.tld");
        RecordingProvider infisical = new RecordingProvider("infisical");

        serviceWith(infisical).writeSecret(SCOPE, "jira-token", "s3cr3t");

        assertThat(infisical.lastWriteKey).isEqualTo("jira-token");
        assertThat(infisical.lastWriteValue).isEqualTo("s3cr3t");
    }

    @Test
    void writeSecret_noVaultBound_fallsBackToTheSettingsVault() {
        RecordingProvider settings = new RecordingProvider(SettingsVaultProvider.TYPE);

        serviceWith(new RecordingProvider("infisical"), settings).writeSecret(SCOPE, "k", "v");

        assertThat(settings.lastWriteKey).isEqualTo("k");
        assertThat(settings.lastWriteValue).isEqualTo("v");
    }

    @Test
    void writeSecret_passesTheScopeThroughToTheProvider() {
        // The settings-backed provider resolves inside Vancetope, so it needs the
        // tenant/project — the binding alone cannot tell it where to write.
        RecordingProvider settings = new RecordingProvider(SettingsVaultProvider.TYPE);

        serviceWith(settings).writeSecret(SCOPE, "k", "v");

        assertThat(settings.lastScope).isEqualTo(SCOPE);
    }

    @Test
    void writeSecret_nullValue_throwsVaultException() {
        assertThatThrownBy(() -> serviceWith(new RecordingProvider("infisical")).writeSecret(SCOPE, "k", null))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    void generateSecret_storesGeneratedAlphanumericOfRequestedLength() {
        setting(PROJECT, "vault.type", "infisical");
        setting(PROJECT, "vault.baseUrl", "https://vault.example.tld");
        RecordingProvider infisical = new RecordingProvider("infisical");

        serviceWith(infisical).generateSecret(SCOPE, "gen", VaultService.SecretFormat.ALPHANUMERIC, 24);

        assertThat(infisical.lastWriteKey).isEqualTo("gen");
        assertThat(infisical.lastWriteValue).hasSize(24).matches("[A-Za-z0-9]+");
    }

    @Test
    void generateValue_hex_hasRequestedLengthAndHexChars() {
        assertThat(VaultService.generateValue(VaultService.SecretFormat.HEX, 16))
                .hasSize(16).matches("[0-9a-f]+");
    }

    @Test
    void generateValue_uuid_ignoresLength() {
        assertThat(VaultService.generateValue(VaultService.SecretFormat.UUID, 999))
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void generateValue_nonPositiveLength_throws() {
        assertThatThrownBy(() -> VaultService.generateValue(VaultService.SecretFormat.ALPHANUMERIC, 0))
                .isInstanceOf(VaultException.class);
    }

    /** In-memory {@link VaultProvider} that records the last binding/write it saw. */
    private static class RecordingProvider implements VaultProvider {
        private final String type;
        private final Map<String, String> store = new HashMap<>();
        private @Nullable VaultBinding lastBinding;
        private @Nullable VaultScope lastScope;
        private @Nullable String lastWriteKey;
        private @Nullable String lastWriteValue;

        RecordingProvider(String type) {
            this.type = type;
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public @Nullable String readSecret(VaultBinding binding, VaultScope scope, String key) {
            this.lastBinding = binding;
            this.lastScope = scope;
            return store.get(key);
        }

        @Override
        public void writeSecret(VaultBinding binding, VaultScope scope, String key, String value) {
            this.lastBinding = binding;
            this.lastScope = scope;
            this.lastWriteKey = key;
            this.lastWriteValue = value;
            store.put(key, value);
        }
    }
}
