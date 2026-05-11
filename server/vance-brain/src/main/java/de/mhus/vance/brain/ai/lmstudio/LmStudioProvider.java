package de.mhus.vance.brain.ai.lmstudio;

import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelProvider;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ai.StandardAiChat;
import de.mhus.vance.brain.ai.ThinkingLevel;
import de.mhus.vance.brain.ai.openai.OpenAiProvider;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * LM Studio backend for {@link AiModelProvider} — local LM Studio server
 * (default {@code http://localhost:1234/v1}) reached via its
 * OpenAI-compatible REST API.
 *
 * <p>Uses the same {@link OpenAiChatModel} / {@link OpenAiStreamingChatModel}
 * classes as {@link de.mhus.vance.brain.ai.openai.OpenAiProvider} with a
 * different default base URL. The {@code apiKey} is forwarded but
 * ignored by LM Studio; operators put any non-blank placeholder in
 * {@code ai.provider.lmstudio.apiKey}.
 */
@Component
@Slf4j
public class LmStudioProvider implements AiModelProvider {

    public static final String NAME = ProviderType.LM_STUDIO.wireName();

    private final String baseUrl;
    private final ModelCatalog modelCatalog;

    public LmStudioProvider(
            ModelCatalog modelCatalog,
            @Value("${vance.ai.lmstudio.base-url:http://localhost:1234/v1}") String baseUrl) {
        this.modelCatalog = modelCatalog;
        this.baseUrl = baseUrl;
    }

    @Override
    public ProviderType getType() {
        return ProviderType.LM_STUDIO;
    }

    @Override
    public AiChat createChat(AiChatConfig config, AiChatOptions options) {
        if (!NAME.equals(config.provider())) {
            throw new AiChatException(
                    "LmStudioProvider received config for provider '" + config.provider() + "'");
        }
        Duration timeout = Duration.ofSeconds(options.getTimeoutSeconds());
        try {
            OpenAiChatModel.OpenAiChatModelBuilder syncBuilder = OpenAiChatModel.builder()
                    .baseUrl(baseUrl)
                    .apiKey(config.apiKey())
                    .modelName(config.modelName())
                    .temperature(options.getTemperature())
                    .maxTokens(options.getMaxTokens())
                    .timeout(timeout)
                    .logRequests(options.getLogRequests())
                    .logResponses(options.getLogRequests());
            OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder streamBuilder =
                    OpenAiStreamingChatModel.builder()
                            .baseUrl(baseUrl)
                            .apiKey(config.apiKey())
                            .modelName(config.modelName())
                            .temperature(options.getTemperature())
                            .maxTokens(options.getMaxTokens())
                            .timeout(timeout)
                            .logRequests(options.getLogRequests())
                            .logResponses(options.getLogRequests());
            String reasoningEffort = OpenAiProvider.mapReasoningEffort(options.getThinkingLevel());
            if (reasoningEffort != null) {
                OpenAiChatRequestParameters defaults = OpenAiChatRequestParameters.builder()
                        .reasoningEffort(reasoningEffort)
                        .build();
                syncBuilder.defaultRequestParameters(defaults);
                streamBuilder.defaultRequestParameters(defaults);
            }
            OpenAiChatModel sync = syncBuilder.build();
            OpenAiStreamingChatModel streaming = streamBuilder.build();
            log.debug("Built LM Studio chat pair: model='{}', baseUrl='{}', maxTokens={}, "
                            + "temperature={}, reasoningEffort={}",
                    config.modelName(), baseUrl, options.getMaxTokens(),
                    options.getTemperature(), reasoningEffort);
            return new StandardAiChat(
                    config.fullName(),
                    ProviderType.LM_STUDIO,
                    modelCatalog.lookupOrDefault(
                            options.getTenantId(), options.getProjectId(),
                            NAME, config.modelName()).capabilities(),
                    sync,
                    streaming,
                    options);
        } catch (RuntimeException e) {
            throw new AiChatException(
                    "Failed to build LM Studio chat for " + config.fullName(), e);
        }
    }
}
