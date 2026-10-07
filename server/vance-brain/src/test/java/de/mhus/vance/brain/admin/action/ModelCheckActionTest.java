package de.mhus.vance.brain.admin.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The model check's contract: aliases over the configured settings — not
 * the whole catalog — one row per alias, one real ping per distinct
 * target, failures as findings (ok=false), not exceptions.
 */
@ExtendWith(MockitoExtension.class)
class ModelCheckActionTest {

    @Mock
    private SettingService settingService;

    @Mock
    private AiModelResolver aiModelResolver;

    @Mock
    private AiModelService aiModelService;

    private final AdminActionContext tenantCtx = new AdminActionContext("acme", null, "op");

    private static SettingDocument doc(String key) {
        SettingDocument d = new SettingDocument();
        d.setTenantId("acme");
        d.setReferenceType(SettingService.SCOPE_PROJECT);
        d.setReferenceId("_tenant");
        d.setKey(key);
        d.setType(de.mhus.vance.api.settings.SettingType.STRING);
        d.setValue("irrelevant-here");
        d.setCreatedAt(Instant.now());
        return d;
    }

    @Test
    void id_scopeAndTitles_areStable() {
        var action = new ModelCheckAction(settingService, aiModelResolver, aiModelService);

        assertThat(action.id()).isEqualTo("model-check");
        assertThat(action.scope()).isEqualTo(AdminActionScope.TENANT_AND_PROJECT);
        assertThat(action.title()).containsKeys("de", "en");
    }

    @Test
    void run_reportsAliasesThatAnswered() {
        // Two aliases → same target: one ping, two rows sharing the outcome.
        when(settingService.findAll("acme", SettingService.SCOPE_PROJECT, "_tenant"))
                .thenReturn(List.of(doc("ai.alias.default.chat"), doc("ai.alias.default.analyze")));
        when(settingService.getStringValue("acme", SettingService.SCOPE_PROJECT, "_tenant", "ai.alias.default.chat"))
                .thenReturn("openai:gpt-4o-mini");
        when(settingService.getStringValue("acme", SettingService.SCOPE_PROJECT, "_tenant", "ai.alias.default.analyze"))
                .thenReturn("openai:gpt-4o-mini");
        when(aiModelResolver.resolveOrDefault("default:chat", "acme", null, null))
                .thenReturn(new AiModelResolver.Resolved("openai", "openai", "gpt-4o-mini"));
        when(aiModelResolver.resolveOrDefault("default:analyze", "acme", null, null))
                .thenReturn(new AiModelResolver.Resolved("openai", "openai", "gpt-4o-mini"));
        stubApiKey();
        AiChat answering = chat("Hallo!");
        when(aiModelService.createChat(any(AiChatConfig.class), any(), any())).thenReturn(answering);

        var result = new ModelCheckAction(settingService, aiModelResolver, aiModelService).run(tenantCtx);

        assertThat(result.isOk()).isTrue();
        assertThat(result.getSummary()).contains("2 of 2").contains("1 distinct models");
        assertThat(result.getItems()).hasSize(2);
        assertThat(result.getItems()).allSatisfy(item -> {
            assertThat(item.isOk()).isTrue();
            assertThat(item.getDetail()).contains("answered");
        });
    }

    @Test
    void run_reportsUnresolvableAliasAsFinding() {
        when(settingService.findAll("acme", SettingService.SCOPE_PROJECT, "_tenant"))
                .thenReturn(List.of(doc("ai.alias.default.chat")));
        when(settingService.getStringValue("acme", SettingService.SCOPE_PROJECT, "_tenant", "ai.alias.default.chat"))
                .thenReturn("bogus:value");
        when(aiModelResolver.resolveOrDefault("default:chat", "acme", null, null))
                .thenThrow(new RuntimeException("Unknown model spec 'default:chat'"));

        var result = new ModelCheckAction(settingService, aiModelResolver, aiModelService).run(tenantCtx);

        assertThat(result.isOk()).isFalse();
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getKey()).isEqualTo("ai.alias.default.chat");
        assertThat(result.getItems().get(0).getDetail()).contains("does not resolve");
    }

    @Test
    void run_reportsFailedPingAsFinding() {
        when(settingService.findAll("acme", SettingService.SCOPE_PROJECT, "_tenant"))
                .thenReturn(List.of(doc("ai.alias.default.chat")));
        when(settingService.getStringValue("acme", SettingService.SCOPE_PROJECT, "_tenant", "ai.alias.default.chat"))
                .thenReturn("openai:gpt-4o-mini");
        when(aiModelResolver.resolveOrDefault("default:chat", "acme", null, null))
                .thenReturn(new AiModelResolver.Resolved("openai", "openai", "gpt-4o-mini"));
        stubApiKey();
        when(aiModelService.createChat(any(AiChatConfig.class), any(), any()))
                .thenThrow(new RuntimeException("401 unauthorized"));

        var result = new ModelCheckAction(settingService, aiModelResolver, aiModelService).run(tenantCtx);

        assertThat(result.isOk()).isFalse();
        assertThat(result.getItems().get(0).getDetail()).contains("401");
        assertThat(result.getSummary()).contains("0 of 1");
    }

    @Test
    void run_withoutAliases_reportsZeroAndOk() {
        when(settingService.findAll("acme", SettingService.SCOPE_PROJECT, "_tenant"))
                .thenReturn(List.of());

        var result = new ModelCheckAction(settingService, aiModelResolver, aiModelService).run(tenantCtx);

        // Nothing configured is not a failure — the summary states it.
        assertThat(result.isOk()).isTrue();
        assertThat(result.getSummary()).contains("0 of 0");
    }

    /**
     * The static {@code ChatBehaviorBuilder.resolveOne} runs for real in
     * these tests — it reads the provider API key through the settings
     * cascade, which is mockable. Satisfying it keeps the ping's config
     * path honest instead of stubbing the builder itself.
     */
    private void stubApiKey() {
        org.mockito.Mockito.when(
                        settingService.getDecryptedPasswordCascade("acme", null, null, "ai.provider.openai.apiKey"))
                .thenReturn("sk-test");
    }

    private static AiChat chat(String answer) {
        AiChat mock = mock(AiChat.class);
        when(mock.ask(any(String.class))).thenReturn(answer);
        return mock;
    }
}
