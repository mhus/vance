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

    // ──── Named Provider Instances ───────────────────────────────────────

    @Test
    void resolve_namedInstance_resolvesViaInstanceType() {
        // `deepseek-direct` is not a ProviderType, but settings declare it
        // as an instance of the openai protocol.
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.provider.deepseek-direct.type")))
                .thenReturn("openai");

        AiModelResolver.Resolved r = resolver.resolve(
                "deepseek-direct:deepseek-v4-flash", "acme", "proj", null);

        assertThat(r.provider()).isEqualTo("openai");
        assertThat(r.providerInstance()).isEqualTo("deepseek-direct");
        assertThat(r.modelName()).isEqualTo("deepseek-v4-flash");
    }

    @Test
    void resolve_directProviderModel_instanceEqualsProvider() {
        // Backward-compat: direct ProviderType spec yields instance == provider.
        AiModelResolver.Resolved r = resolver.resolve(
                "openai:gpt-4o-mini", "acme", null, null);

        assertThat(r.provider()).isEqualTo("openai");
        assertThat(r.providerInstance()).isEqualTo("openai");
        assertThat(r.modelName()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void resolve_namedInstance_unknownType_throws() {
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.provider.bogus-instance.type")))
                .thenReturn("not-a-real-provider");

        assertThatThrownBy(() -> resolver.resolve(
                "bogus-instance:some-model", "acme", null, null))
                .isInstanceOf(AiModelResolver.UnknownModelException.class)
                .hasMessageContaining("bogus-instance")
                .hasMessageContaining("not-a-real-provider");
    }

    @Test
    void resolve_namedInstance_winsOverAliasLookup() {
        // If a prefix has BOTH ai.provider.<prefix>.type AND
        // ai.alias.<prefix>.<rest>, the instance binding wins — that's the
        // documented order: ProviderType → instance → alias.
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.provider.deepseek-direct.type")))
                .thenReturn("openai");
        // Alias setting still being stubbed shouldn't be consulted.
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.alias.deepseek-direct.deepseek-v4-flash")))
                .thenReturn("gemini:should-not-be-used");

        AiModelResolver.Resolved r = resolver.resolve(
                "deepseek-direct:deepseek-v4-flash", "acme", null, null);

        assertThat(r.provider()).isEqualTo("openai");
        assertThat(r.providerInstance()).isEqualTo("deepseek-direct");
        assertThat(r.modelName()).isEqualTo("deepseek-v4-flash");
    }

    @Test
    void resolve_aliasInto_namedInstance() {
        // default:analyze → deepseek-direct:deepseek-v4-flash, where
        // deepseek-direct is a named instance of openai.
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.alias.default.analyze")))
                .thenReturn("deepseek-direct:deepseek-v4-flash");
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.provider.deepseek-direct.type")))
                .thenReturn("openai");

        AiModelResolver.Resolved r = resolver.resolve(
                "default:analyze", "acme", null, null);

        assertThat(r.provider()).isEqualTo("openai");
        assertThat(r.providerInstance()).isEqualTo("deepseek-direct");
        assertThat(r.modelName()).isEqualTo("deepseek-v4-flash");
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

    // ──── Comma-cascade ─────────────────────────────────────────────────

    @Test
    void cascade_firstElementDefined_winsImmediately() {
        // default:arthur is configured → cascade stops at element 1.
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.alias.default.arthur")))
                .thenReturn("anthropic:claude-opus-4");

        AiModelResolver.Resolved r = resolver.resolve(
                "default:arthur,default:chat", "acme", "proj", null);

        assertThat(r.provider()).isEqualTo("anthropic");
        assertThat(r.modelName()).isEqualTo("claude-opus-4");
    }

    @Test
    void cascade_firstElementUndefined_fallsToSecond() {
        // default:arthur is NOT configured (returns null);
        // default:chat is configured → second wins.
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.alias.default.arthur")))
                .thenReturn(null);
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.alias.default.chat")))
                .thenReturn("gemini:gemini-2.5-flash");

        AiModelResolver.Resolved r = resolver.resolve(
                "default:arthur,default:chat", "acme", "proj", null);

        assertThat(r.provider()).isEqualTo("gemini");
        assertThat(r.modelName()).isEqualTo("gemini-2.5-flash");
    }

    @Test
    void cascade_allDefaultElementsUndefined_lastFallsToTenantDefault() {
        // Neither alias configured; last element is "default:" → safety net.
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.alias.default.arthur")))
                .thenReturn(null);
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.alias.default.chat")))
                .thenReturn(null);
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.default.provider")))
                .thenReturn("anthropic");
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.default.model")))
                .thenReturn("claude-haiku-4-5");

        AiModelResolver.Resolved r = resolver.resolve(
                "default:arthur,default:chat", "acme", null, null);

        assertThat(r.provider()).isEqualTo("anthropic");
        assertThat(r.modelName()).isEqualTo("claude-haiku-4-5");
    }

    @Test
    void cascade_nonDefaultLastElementUndefined_throws() {
        // Last element prefix is "cheap" (not "default") and unconfigured —
        // no safety net applies, must throw.
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.alias.default.arthur")))
                .thenReturn(null);
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.alias.cheap.lookup")))
                .thenReturn(null);

        assertThatThrownBy(() -> resolver.resolve(
                "default:arthur,cheap:lookup", "acme", null, null))
                .isInstanceOf(AiModelResolver.UnknownModelException.class)
                .hasMessageContaining("cheap:lookup");
    }

    @Test
    void cascade_directProviderAsElement_winsRegardlessOfPosition() {
        // First element undefined, second is a direct provider:model → second wins.
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.alias.default.arthur")))
                .thenReturn(null);

        AiModelResolver.Resolved r = resolver.resolve(
                "default:arthur,openai:gpt-4o-mini", "acme", null, null);

        assertThat(r.provider()).isEqualTo("openai");
        assertThat(r.modelName()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void cascade_namedInstanceAsElement_winsViaInstanceType() {
        // First element undefined, second is a named instance → second wins.
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.alias.default.arthur")))
                .thenReturn(null);
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.provider.deepseek-direct.type")))
                .thenReturn("openai");

        AiModelResolver.Resolved r = resolver.resolve(
                "default:arthur,deepseek-direct:deepseek-v4-flash", "acme", null, null);

        assertThat(r.provider()).isEqualTo("openai");
        assertThat(r.providerInstance()).isEqualTo("deepseek-direct");
        assertThat(r.modelName()).isEqualTo("deepseek-v4-flash");
    }

    @Test
    void cascade_whitespaceAroundCommas_isTrimmed() {
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.alias.default.arthur")))
                .thenReturn(null);
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.alias.default.chat")))
                .thenReturn("gemini:gemini-2.5-flash");

        AiModelResolver.Resolved r = resolver.resolve(
                "  default:arthur ,  default:chat  ", "acme", null, null);

        assertThat(r.provider()).isEqualTo("gemini");
        assertThat(r.modelName()).isEqualTo("gemini-2.5-flash");
    }

    @Test
    void cascade_emptyElementsBetweenCommas_areSkipped() {
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.alias.default.arthur")))
                .thenReturn(null);
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.alias.default.chat")))
                .thenReturn("gemini:gemini-2.5-flash");

        AiModelResolver.Resolved r = resolver.resolve(
                "default:arthur,,default:chat", "acme", null, null);

        assertThat(r.provider()).isEqualTo("gemini");
        assertThat(r.modelName()).isEqualTo("gemini-2.5-flash");
    }

    @Test
    void cascade_singleElement_behavesIdenticallyToPreCascade() {
        // No comma → identical to before: configured alias resolves normally.
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.alias.default.fast")))
                .thenReturn("gemini:gemini-2.5-flash");

        AiModelResolver.Resolved r = resolver.resolve(
                "default:fast", "acme", null, null);

        assertThat(r.provider()).isEqualTo("gemini");
        assertThat(r.modelName()).isEqualTo("gemini-2.5-flash");
    }

    @Test
    void cascade_insideAliasTargetValue_isHonored() {
        // default:chat alias-value is itself a cascade. First sub-element
        // (cheap:lookup) undefined → second (gemini:...) wins.
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.alias.default.chat")))
                .thenReturn("cheap:lookup,gemini:gemini-2.5-flash");
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.alias.cheap.lookup")))
                .thenReturn(null);

        AiModelResolver.Resolved r = resolver.resolve(
                "default:chat", "acme", null, null);

        assertThat(r.provider()).isEqualTo("gemini");
        assertThat(r.modelName()).isEqualTo("gemini-2.5-flash");
    }

    @Test
    void cascade_cycleAcrossAliasTargets_isDetected() {
        // default:a → "default:b" alias-value; default:b → "default:a" alias-value.
        // Each cascade-element starts with a fresh seen-set copy, so the
        // cycle is detected within an alias-chain.
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.alias.default.a")))
                .thenReturn("default:b");
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.alias.default.b")))
                .thenReturn("default:a");

        assertThatThrownBy(() -> resolver.resolve(
                "default:a,default:c", "acme", null, null))
                .isInstanceOf(AiModelResolver.UnknownModelException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void cascade_undefinedFirstElement_doesNotFallToTenantDefault() {
        // The key cascade contract: a non-last "default:foo" alias-miss
        // must NOT trigger the tenant-default safety-net; it must skip
        // to the next cascade element instead.
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.alias.default.arthur")))
                .thenReturn(null);
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.alias.default.chat")))
                .thenReturn("openai:gpt-4o-mini");
        // ai.default.provider/model are configured — but should NOT win,
        // because the cascade has a second, defined element.
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.default.provider")))
                .thenReturn("anthropic");
        when(settingService.getStringValueCascade(
                any(), any(), any(), eq("ai.default.model")))
                .thenReturn("claude-haiku-4-5");

        AiModelResolver.Resolved r = resolver.resolve(
                "default:arthur,default:chat", "acme", null, null);

        // openai (cascade element wins), not anthropic (tenant default).
        assertThat(r.provider()).isEqualTo("openai");
        assertThat(r.modelName()).isEqualTo("gpt-4o-mini");
    }
}
