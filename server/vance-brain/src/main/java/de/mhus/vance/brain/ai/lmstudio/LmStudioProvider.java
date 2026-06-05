package de.mhus.vance.brain.ai.lmstudio;

import de.mhus.vance.brain.ai.AbstractChatProvider;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.LlmResponseSanitizer;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.ProviderType;
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
 * LM Studio backend — local LM Studio server (default
 * {@code http://localhost:1234/v1}) reached via its OpenAI-compatible
 * REST API.
 *
 * <p>Uses the same {@link OpenAiChatModel} / {@link OpenAiStreamingChatModel}
 * classes as {@link OpenAiProvider} with a different default base URL.
 * The {@code apiKey} is forwarded but ignored by LM Studio; operators
 * put any non-blank placeholder in {@code ai.provider.lmstudio.apiKey}.
 *
 * <p>Cross-cutting orchestration lives in {@link AbstractChatProvider}.
 */
@Component
@Slf4j
public class LmStudioProvider extends AbstractChatProvider {

    private final String defaultBaseUrl;

    public LmStudioProvider(
            ModelCatalog modelCatalog,
            LlmResponseSanitizer responseSanitizer,
            @Value("${vance.ai.lmstudio.base-url:http://localhost:1234/v1}") String baseUrl) {
        super(modelCatalog, responseSanitizer);
        this.defaultBaseUrl = baseUrl;
    }

    @Override
    public ProviderType getType() {
        return ProviderType.LM_STUDIO;
    }

    @Override
    protected BuiltChat buildModels(
            AiChatConfig config, AiChatOptions options, ModelInfo modelInfo) {
        Duration timeout = Duration.ofSeconds(
                modelInfo.effectiveTimeoutSeconds(options.getTimeoutSeconds()));
        Integer seed = options.getSeed() == null ? null : options.getSeed().intValue();
        // Per-tenant override (custom LM Studio host) wins over the Spring
        // boot-time default. Empty / unset falls back to vance.ai.lmstudio.base-url.
        String baseUrl = config.baseUrl() != null ? config.baseUrl() : defaultBaseUrl;
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
        log.debug("Built LM Studio chat pair: model='{}', baseUrl='{}', maxTokens={}, "
                        + "temperature={}, reasoningEffort={}",
                config.modelName(), baseUrl, options.getMaxTokens(),
                options.getTemperature(), reasoningEffort);
        return new BuiltChat(syncBuilder.build(), streamBuilder.build());
    }
}
