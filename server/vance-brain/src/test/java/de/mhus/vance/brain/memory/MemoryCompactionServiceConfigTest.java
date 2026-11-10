package de.mhus.vance.brain.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.ford.FordProperties;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The summarizer's model comes from the tenant default
 * ({@code ai.default.provider} / {@code ai.default.model}). The provider value
 * can be a named instance — it must be resolved, not read as a wire-name.
 */
class MemoryCompactionServiceConfigTest {

    private SettingService settingService;
    private AiModelResolver aiModelResolver;
    private MemoryCompactionService service;

    @BeforeEach
    void setUp() {
        settingService = mock(SettingService.class);
        aiModelResolver = mock(AiModelResolver.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.findBySessionId(any())).thenReturn(Optional.empty());
        de.mhus.vance.brain.prak.PrakProperties prakProps = new de.mhus.vance.brain.prak.PrakProperties();
        service = new MemoryCompactionService(
                mock(ChatMessageService.class),
                mock(MemoryService.class),
                mock(AiModelService.class),
                sessionService,
                settingService,
                new FordProperties(),
                mock(LlmCallTracker.class),
                mock(ProgressEmitter.class),
                new MetricService(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                mock(de.mhus.vance.brain.prak.PrakSideChannelRunner.class),
                new StrengthAwareSelector(prakProps, new de.mhus.vance.brain.prak.TrivialPatterns()),
                new CompactionTriggerService(prakProps),
                prakProps,
                mock(de.mhus.vance.brain.prak.PrakPeriodicTrigger.class),
                mock(de.mhus.vance.shared.settings.LanguageResolver.class),
                aiModelResolver);
    }

    private static ThinkProcessDocument process() {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId("p-1");
        p.setTenantId("t");
        p.setSessionId("s");
        return p;
    }

    @Test
    void namedInstanceDefault_isResolvedToItsProtocolAndInstance() {
        when(settingService.getStringValueCascade("t", null, "p-1", "ai.default.provider"))
                .thenReturn("openrouter");
        when(settingService.getStringValueCascade("t", null, "p-1", "ai.default.model"))
                .thenReturn("qwen/qwen3.7-flash");
        when(aiModelResolver.resolveOrDefault(null, "t", null, "p-1"))
                .thenReturn(new AiModelResolver.Resolved("openai", "openrouter", "qwen/qwen3.7-flash", false));
        when(settingService.getDecryptedPasswordCascade("t", null, "p-1", "ai.provider.openrouter.apiKey"))
                .thenReturn("key-or");

        AiChatConfig config = service.resolveAiConfig(process());

        assertThat(config.provider()).isEqualTo("openai");
        assertThat(config.providerInstance()).isEqualTo("openrouter");
        assertThat(config.modelName()).isEqualTo("qwen/qwen3.7-flash");
        assertThat(config.apiKey()).isEqualTo("key-or");
    }

    @Test
    void withoutAConfiguredDefault_theBuiltInFallbackApplies() {
        when(settingService.getDecryptedPasswordCascade("t", null, "p-1", "ai.provider.anthropic.apiKey"))
                .thenReturn("key-an");

        AiChatConfig config = service.resolveAiConfig(process());

        assertThat(config.provider()).isEqualTo("anthropic");
        assertThat(config.providerInstance()).isEqualTo("anthropic");
        assertThat(config.modelName()).isEqualTo("claude-sonnet-4-5");
    }
}
