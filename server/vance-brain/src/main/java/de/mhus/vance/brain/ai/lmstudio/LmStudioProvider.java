package de.mhus.vance.brain.ai.lmstudio;

import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelProvider;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
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

    private final String defaultBaseUrl;
    private final ModelCatalog modelCatalog;

    public LmStudioProvider(
            ModelCatalog modelCatalog,
            @Value("${vance.ai.lmstudio.base-url:http://localhost:1234/v1}") String baseUrl) {
        this.modelCatalog = modelCatalog;
        this.defaultBaseUrl = baseUrl;
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
        ModelInfo modelInfo = modelCatalog.lookupOrDefault(
                options.getTenantId(), options.getProjectId(),
                NAME, config.modelName());
        Duration timeout = Duration.ofSeconds(
                modelInfo.effectiveTimeoutSeconds(options.getTimeoutSeconds()));
        Integer seed = options.getSeed() == null ? null : options.getSeed().intValue();
        // Per-tenant override (custom LM Studio host) wins over the Spring
        // boot-time default. Empty / unset falls back to vance.ai.lmstudio.base-url.
        String baseUrl = config.baseUrl() != null ? config.baseUrl() : defaultBaseUrl;
        try {
            OpenAiChatModel.OpenAiChatModelBuilder syncBuilder = OpenAiChatModel.builder()
                    .baseUrl(baseUrl)
                    .apiKey(config.apiKey())
                    .modelName(config.modelName())
                    .temperature(options.getTemperature())
                    .maxTokens(options.getMaxTokens())
                    .topP(options.getTopP())
                    .frequencyPenalty(options.getFrequencyPenalty())
                    .presencePenalty(options.getPresencePenalty())
                    .seed(seed)
                    .stop(options.getStopSequences())
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
                            .topP(options.getTopP())
                            .frequencyPenalty(options.getFrequencyPenalty())
                            .presencePenalty(options.getPresencePenalty())
                            .seed(seed)
                            .stop(options.getStopSequences())
                            .timeout(timeout)
                            .logRequests(options.getLogRequests())
                            .logResponses(options.getLogRequests());
            ThinkingLevel effectiveLevel = OpenAiProvider.gateThinkingLevel(
                    options.getThinkingLevel(), modelInfo);
            String reasoningEffort = OpenAiProvider.mapReasoningEffort(effectiveLevel);
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
                    modelInfo.capabilities(),
                    sync,
                    streaming,
                    options);
        } catch (RuntimeException e) {
            throw new AiChatException(
                    "Failed to build LM Studio chat for " + config.fullName(), e);
        }
    }
}
