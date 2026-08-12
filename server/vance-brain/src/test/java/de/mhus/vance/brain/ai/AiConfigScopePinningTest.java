package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code params.aiScope} decides which settings layers a process's chat
 * endpoint is resolved from — see {@link AiConfigScope}.
 *
 * <p>The bug this guards against: model name and endpoint config are
 * separate cascade lookups. A project that overrides only
 * {@code ai.provider.<instance>.baseUrl} while inheriting the model from
 * {@code _tenant} sends the tenant's model to the project's endpoint
 * (observed as {@code deepseek-v4-pro} against {@code api.openai.com}).
 * Pinning to the tenant layer makes both come from one layer.
 */
class AiConfigScopePinningTest {

    @Test
    void fromProcess_tenantScope_resolvesWithoutProjectAndProcessLayers() {
        AiModelResolver resolver = mock(AiModelResolver.class);
        SettingService settings = mock(SettingService.class);
        when(resolver.resolveOrDefault(any(), any(), any(), any()))
                .thenReturn(AiModelResolver.Resolved.direct("openai", "gpt-5.6-luna"));
        when(settings.getDecryptedPasswordCascade(any(), any(), any(), any()))
                .thenReturn("sk-tenant");

        ChatBehaviorBuilder.fromProcess(
                process(Map.of("model", "default:fast",
                        AiConfigScope.PARAM_KEY, "tenant")),
                settings, resolver);

        // Model spec, api-key and base-URL all read with both inner scopes
        // nulled — that is what collapses the cascade onto _tenant.
        verify(resolver).resolveOrDefault(
                eq("default:fast"), eq("acme"), isNull(), isNull());
        verify(settings).getDecryptedPasswordCascade(
                eq("acme"), isNull(), isNull(), eq("ai.provider.openai.apiKey"));
        verify(settings).getStringValueCascade(
                eq("acme"), isNull(), isNull(), eq("ai.provider.openai.baseUrl"));
    }

    @Test
    void fromProcess_defaultScope_resolvesThroughProjectCascade() {
        AiModelResolver resolver = mock(AiModelResolver.class);
        SettingService settings = mock(SettingService.class);
        when(resolver.resolveOrDefault(any(), any(), any(), any()))
                .thenReturn(AiModelResolver.Resolved.direct("openai", "gpt-5.6-luna"));
        when(settings.getDecryptedPasswordCascade(any(), any(), any(), any()))
                .thenReturn("sk-project");

        ChatBehaviorBuilder.fromProcess(
                process(Map.of("model", "default:fast")), settings, resolver);

        verify(resolver).resolveOrDefault(
                eq("default:fast"), eq("acme"), eq("proj-1"), eq("proc-1"));
        verify(settings).getStringValueCascade(
                eq("acme"), eq("proj-1"), eq("proc-1"), eq("ai.provider.openai.baseUrl"));
    }

    @Test
    void resolveForProcess_tenantScope_alsoSkipsProjectLayer() {
        AiModelResolver resolver = mock(AiModelResolver.class);
        SettingService settings = mock(SettingService.class);
        when(resolver.resolveOrDefault(any(), any(), any(), any()))
                .thenReturn(AiModelResolver.Resolved.direct("openai", "gpt-5.6-luna"));
        when(settings.getDecryptedPasswordCascade(any(), any(), any(), any()))
                .thenReturn("sk-tenant");

        ChatBehaviorBuilder.resolveForProcess(
                process(Map.of(AiConfigScope.PARAM_KEY, "tenant")), settings, resolver);

        verify(resolver).resolveOrDefault(isNull(), eq("acme"), isNull(), isNull());
    }

    @Test
    void readAiConfigScope_runtimeOverrideWins() {
        ThinkProcessDocument p = process(Map.of(AiConfigScope.PARAM_KEY, "cascade"));
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put(AiConfigScope.PARAM_KEY, "tenant");
        p.setEngineParamOverrides(overrides);

        assertThat(ChatBehaviorBuilder.readAiConfigScope(p))
                .isEqualTo(AiConfigScope.TENANT);
    }

    @Test
    void readAiConfigScope_unknownValueFallsBackToCascade() {
        assertThat(ChatBehaviorBuilder.readAiConfigScope(
                process(Map.of(AiConfigScope.PARAM_KEY, "galaxy"))))
                .isEqualTo(AiConfigScope.CASCADE);
    }

    @Test
    void readAiConfigScope_wrongTypeFallsBackToCascade() {
        assertThat(ChatBehaviorBuilder.readAiConfigScope(
                process(Map.of(AiConfigScope.PARAM_KEY, 42))))
                .isEqualTo(AiConfigScope.CASCADE);
    }

    @Test
    void readAiConfigScope_missingParamIsCascade() {
        assertThat(ChatBehaviorBuilder.readAiConfigScope(process(Map.of())))
                .isEqualTo(AiConfigScope.CASCADE);
    }

    private static ThinkProcessDocument process(Map<String, Object> params) {
        return ThinkProcessDocument.builder()
                .id("proc-1")
                .tenantId("acme")
                .projectId("proj-1")
                .sessionId("sess-1")
                .name("diagnose-1")
                .engineParams(new LinkedHashMap<>(params))
                .build();
    }
}
