package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.settings.SettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the alias-cascade resolution logic. Covers each branch of
 * the documented {@code <prefix>:<rest>}-rule:
 * <ol>
 *   <li>Direct provider:model — short-circuit.</li>
 *   <li>Configured alias — recursion.</li>
 *   <li>Unconfigured "default:" — falls back to tenant defaults.</li>
 *   <li>Unconfigured non-"default:" alias — throws.</li>
 * </ol>
 * Plus cycle detection and depth-limit (the safety net).
 */
class AiModelResolverTest {

    private AiModelService aiModelService;
    private SettingService settingService;
    private AiModelResolver resolver;

    @BeforeEach
    void setUp() {
        aiModelService = mock(AiModelService.class);
        settingService = mock(SettingService.class);
        resolver = new AiModelResolver(aiModelService, settingService);

        // Set up known providers — the resolver short-circuits on these.
        when(aiModelService.hasProvider("anthropic")).thenReturn(true);
        when(aiModelService.hasProvider("gemini")).thenReturn(true);
        when(aiModelService.hasProvider("openai")).thenReturn(true);
    }

    // ──── Direct provider:model ──────────────────────────────────────────

    @Test
    void resolve_directProviderModel_shortCircuits() {
        AiModelResolver.Resolved r = resolver.resolve(
                "anthropic:claude-sonnet-4-5", "acme", "proj", null);

        assertThat(r.provider()).isEqualTo("anthropic");
        assertThat(r.modelName()).isEqualTo("claude-sonnet-4-5");
    }

    @Test
    void resolve_trimsWhitespace() {
        AiModelResolver.Resolved r = resolver.resolve(
                "  anthropic:claude-sonnet-4-5  ", "acme", "proj", null);

        assertThat(r.provider()).isEqualTo("anthropic");
        assertThat(r.modelName()).isEqualTo("claude-sonnet-4-5");
    }

    // ──── Alias cascade ──────────────────────────────────────────────────

    @Test
    void resolve_aliasNamespace_recursesIntoConfiguredValue() {
        when(settingService.getStringValueCascade(
                "acme", "proj", null, "ai.alias.default.fast"))
                .thenReturn("gemini:gemini-2.5-flash");

        AiModelResolver.Resolved r = resolver.resolve(
                "default:fast", "acme", "proj", null);

        assertThat(r.provider()).isEqualTo("gemini");
        assertThat(r.modelName()).isEqualTo("gemini-2.5-flash");
    }

    @Test
    void resolve_aliasChain_recursesMultipleHops() {
        // default:tier1 → cheap:lookup → gemini:gemini-2.5-flash
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.alias.default.tier1")))
                .thenReturn("cheap:lookup");
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.alias.cheap.lookup")))
                .thenReturn("gemini:gemini-2.5-flash");

        AiModelResolver.Resolved r = resolver.resolve(
                "default:tier1", "acme", null, null);

        assertThat(r.provider()).isEqualTo("gemini");
        assertThat(r.modelName()).isEqualTo("gemini-2.5-flash");
    }

    @Test
    void resolve_unknownDefaultAlias_fallsBackToTenantDefaults() {
        // Alias is not configured; key starts with "default:" → tenant fallback.
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.alias.default.unknown")))
                .thenReturn(null);
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.default.provider")))
                .thenReturn("anthropic");
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.default.model")))
                .thenReturn("claude-sonnet-4-5");

        AiModelResolver.Resolved r = resolver.resolve(
                "default:unknown", "acme", null, null);

        assertThat(r.provider()).isEqualTo("anthropic");
        assertThat(r.modelName()).isEqualTo("claude-sonnet-4-5");
    }

    @Test
    void resolve_unknownNonDefaultAlias_throws() {
        // Non-"default" namespace with no alias configured must NOT fall back —
        // surface the misconfiguration loudly.
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.alias.cheap.unknown")))
                .thenReturn(null);

        assertThatThrownBy(() -> resolver.resolve(
                "cheap:unknown", "acme", null, null))
                .isInstanceOf(AiModelResolver.UnknownModelException.class)
                .hasMessageContaining("cheap:unknown")
                .hasMessageContaining("ai.alias.cheap.unknown");
    }

    @Test
    void resolveOrDefault_blankInput_usesTenantDefaults() {
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.default.provider")))
                .thenReturn("openai");
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.default.model")))
                .thenReturn("gpt-4o-mini");

        AiModelResolver.Resolved r = resolver.resolveOrDefault(
                null, "acme", null, null);

        assertThat(r.provider()).isEqualTo("openai");
        assertThat(r.modelName()).isEqualTo("gpt-4o-mini");

        // And empty string treated the same.
        AiModelResolver.Resolved r2 = resolver.resolveOrDefault(
                "  ", "acme", null, null);
        assertThat(r2.provider()).isEqualTo("openai");
    }

    @Test
    void tenantDefault_throws_whenSettingsMissing() {
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.default.provider")))
                .thenReturn(null);
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.default.model")))
                .thenReturn(null);

        assertThatThrownBy(() -> resolver.resolveOrDefault(
                null, "acme", null, null))
                .isInstanceOf(AiModelResolver.UnknownModelException.class)
                .hasMessageContaining("ai.default.provider");
    }

    // ──── Cycle and depth protection ─────────────────────────────────────

    @Test
    void resolve_cyclicAlias_throws() {
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.alias.default.a")))
                .thenReturn("default:b");
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.alias.default.b")))
                .thenReturn("default:a");

        assertThatThrownBy(() -> resolver.resolve(
                "default:a", "acme", null, null))
                .isInstanceOf(AiModelResolver.UnknownModelException.class)
                .hasMessageContaining("cycle");
    }

    // ──── Validation ─────────────────────────────────────────────────────

    @Test
    void resolve_malformedSpec_throws() {
        // No colon
        assertThatThrownBy(() -> resolver.resolve("just-a-name", "acme", null, null))
                .isInstanceOf(AiModelResolver.UnknownModelException.class);

        // Empty prefix
        assertThatThrownBy(() -> resolver.resolve(":model", "acme", null, null))
                .isInstanceOf(AiModelResolver.UnknownModelException.class);

        // Empty suffix
        assertThatThrownBy(() -> resolver.resolve("provider:", "acme", null, null))
                .isInstanceOf(AiModelResolver.UnknownModelException.class);
    }
}
