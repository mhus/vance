package de.mhus.vance.anus.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure half of the tenant-setup agent mode: YAML config →
 * typed parse, fail-closed on unknown keys / wrong types / missing values,
 * all problems reported at once. The database-dependent "ensure" half is
 * covered by {@code qa/ai-test} end-to-end runs, not here.
 */
class SetupConfigParserTest {

    /** Parses real YAML text the way the wizard reads it. */
    private static Map<String, Object> yaml(String text) {
        Object root = new org.yaml.snakeyaml.Yaml().load(text);
        assertThat(root).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) root;
        return map;
    }

    @Test
    void fullValidConfig_parsesToTypedFields() {
        SetupConfigParser.Parsed p = SetupConfigParser.parse(yaml("""
                tenant:
                  name: acme
                  title: Acme Corp
                user:
                  name: admin
                  title: Admin
                  email: ops@example.com
                  password: S3cret-Pass!
                ai:
                  provider: custom
                  instance: cortecs
                  model: deepseek-chat
                  base-url: https://api.cortecs.ai/v1
                  api-key: sk-123
                serper-key: serper-xyz
                """));

        assertThat(p.tenantName()).isEqualTo("acme");
        assertThat(p.tenantTitle()).isEqualTo("Acme Corp");
        assertThat(p.userName()).isEqualTo("admin");
        assertThat(p.userPassword()).isEqualTo("S3cret-Pass!");
        assertThat(p.provider()).isEqualTo(ProviderPreset.CUSTOM);
        assertThat(p.instanceName()).isEqualTo("cortecs");
        assertThat(p.aiModel()).isEqualTo("deepseek-chat");
        assertThat(p.baseUrl()).isEqualTo("https://api.cortecs.ai/v1");
        assertThat(p.aiApiKey()).isEqualTo("sk-123");
        assertThat(p.serperKey()).isEqualTo("serper-xyz");
        assertThat(p.aiConfigured()).isTrue();
    }

    @Test
    void fixedProvider_defaultsModelAndRejectsInstance() {
        SetupConfigParser.Parsed p = SetupConfigParser.parse(yaml("""
                tenant: {name: acme}
                user: {name: admin, password: S3cret-Pass!}
                ai: {provider: gemini}
                """));

        assertThat(p.provider()).isEqualTo(ProviderPreset.GEMINI);
        // Same default the interactive wizard applies when picking the preset.
        assertThat(p.aiModel()).isEqualTo(ProviderPreset.GEMINI.defaultModel());
    }

    @Test
    void missingAiSection_isADeliberateSkip() {
        SetupConfigParser.Parsed p = SetupConfigParser.parse(yaml("""
                tenant: {name: acme}
                user: {name: admin, password: S3cret-Pass!}
                """));

        assertThat(p.aiConfigured()).isFalse();
        assertThat(p.provider()).isNull();
    }

    @Test
    void providerNone_clearsAi() {
        SetupConfigParser.Parsed p = SetupConfigParser.parse(yaml("""
                tenant: {name: acme}
                user: {name: admin, password: S3cret-Pass!}
                ai: {provider: none}
                """));

        assertThat(p.aiConfigured()).isFalse();
    }

    @Test
    void customProvider_needsInstanceBaseUrlAndModel() {
        assertThatThrownBy(() -> SetupConfigParser.parse(yaml("""
                tenant: {name: acme}
                user: {name: admin, password: S3cret-Pass!}
                ai: {provider: custom}
                """)))
                .isInstanceOf(SetupConfigParser.SetupConfigException.class)
                .hasMessageContaining("ai.instance: required for provider 'custom'")
                .hasMessageContaining("ai.base-url: required for provider 'custom'")
                .hasMessageContaining("ai.model: required when an AI provider is configured");
    }

    @Test
    void unknownProvider_isRejected() {
        assertThatThrownBy(() -> SetupConfigParser.parse(yaml("""
                tenant: {name: acme}
                user: {name: admin, password: S3cret-Pass!}
                ai: {provider: openrouter}
                """)))
                .isInstanceOf(SetupConfigParser.SetupConfigException.class)
                .hasMessageContaining("ai.provider: unknown 'openrouter'");
    }

    @Test
    void instanceName_isNormalised() {
        SetupConfigParser.Parsed p = SetupConfigParser.parse(yaml("""
                tenant: {name: acme}
                user: {name: admin, password: S3cret-Pass!}
                ai: {provider: custom, instance: Cortecs, model: m, base-url: https://x, api-key: k}
                """));

        // "Cortecs" becomes the settings key it will be written under — the
        // mixed-case namespace split is exactly the failure this prevents.
        assertThat(p.instanceName()).isEqualTo("cortecs");
    }

    @Test
    void missingTenantAndUser_isRejectedNamingBoth() {
        assertThatThrownBy(() -> SetupConfigParser.parse(yaml("serper-key: k\n")))
                .isInstanceOf(SetupConfigParser.SetupConfigException.class)
                .hasMessageContaining("tenant.name: required")
                .hasMessageContaining("user.name: required");
    }

    @Test
    void systemTenantAndServiceAccountNames_areRejected() {
        assertThatThrownBy(() -> SetupConfigParser.parse(yaml("""
                tenant: {name: _vance}
                user: {name: _svc, password: S3cret-Pass!}
                """)))
                .isInstanceOf(SetupConfigParser.SetupConfigException.class)
                .hasMessageContaining("'_vance' is reserved for internal use")
                .hasMessageContaining("reserved for service accounts");
    }

    @Test
    void unknownKeys_areRejectedNamingTheKey() {
        assertThatThrownBy(() -> SetupConfigParser.parse(yaml("""
                tenant: {name: acme, nam: acme}
                user: {name: admin, password: S3cret-Pass!}
                typo: 1
                """)))
                .isInstanceOf(SetupConfigParser.SetupConfigException.class)
                .hasMessageContaining("tenant.nam: unknown setting")
                .hasMessageContaining("typo: unknown setting");
    }

    @Test
    void wrongType_isRejected() {
        assertThatThrownBy(() -> SetupConfigParser.parse(yaml("""
                tenant: {name: acme}
                user: {name: admin, password: [not, a, string]}
                """)))
                .isInstanceOf(SetupConfigParser.SetupConfigException.class)
                .hasMessageContaining("user.password: expected a string");
    }

    @Test
    void nonStringScalarWhereMappingExpected_isRejected() {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("tenant", "acme");

        assertThatThrownBy(() -> SetupConfigParser.parse(bad))
                .isInstanceOf(SetupConfigParser.SetupConfigException.class)
                .hasMessageContaining("tenant: expected a mapping");
    }
}
