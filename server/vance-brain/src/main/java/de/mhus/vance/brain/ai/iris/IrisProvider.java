package de.mhus.vance.brain.ai.iris;

import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.DiscoveredModelInfo;
import de.mhus.vance.brain.ai.EmptyResponseEvidenceStore;
import de.mhus.vance.brain.ai.LlmResponseSanitizer;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.ProviderListingRequest;
import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ai.UsageGate;
import de.mhus.vance.brain.ai.UsageSink;
import de.mhus.vance.brain.ai.openai.OpenAiProvider;
import de.mhus.vance.brain.ai.parser.MessageParserRegistry;
import dev.langchain4j.http.client.HttpClientBuilder;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Iris, the paid-usage model gateway. Chat and embeddings are the OpenAI chat
 * wire, so this adapter is the OpenAI one with a different type and with Iris'
 * refusals made typed (see {@link IrisErrors}).
 *
 * <p>There is deliberately no default base URL: an Iris key sent to
 * api.openai.com would be a leak, and the instance's own
 * {@code ai.provider.<instance>.baseUrl} is the only valid source. The
 * credentials form for the instance marks it required.
 */
@Component
public class IrisProvider extends OpenAiProvider {

    public IrisProvider(
            ModelCatalog modelCatalog,
            LlmResponseSanitizer responseSanitizer,
            MessageParserRegistry messageParserRegistry,
            UsageSink usageSink,
            UsageGate usageGate,
            EmptyResponseEvidenceStore emptyResponseEvidenceStore,
            @Value("${vance.ai.openai.base-url:}") String baseUrl,
            @Value("${vance.ai.cache.enabled:true}") boolean cacheEnabled) {
        super(
                modelCatalog,
                responseSanitizer,
                messageParserRegistry,
                usageSink,
                usageGate,
                emptyResponseEvidenceStore,
                baseUrl,
                cacheEnabled);
    }

    @Override
    public ProviderType getType() {
        return ProviderType.IRIS;
    }

    @Override
    protected BuiltChat buildModels(AiChatConfig config, AiChatOptions options, ModelInfo modelInfo) {
        requireBaseUrl(config.baseUrl(), config.providerInstance());
        return super.buildModels(config, options, modelInfo);
    }

    @Override
    public List<DiscoveredModelInfo> listAvailableModels(ProviderListingRequest req) {
        requireBaseUrl(req.baseUrl(), req.providerInstance());
        return super.listAvailableModels(req);
    }

    /** No fallback to OpenAI's endpoint: the Iris key would go to the wrong party. */
    static void requireBaseUrl(@Nullable String baseUrl, String instance) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new AiChatException("Provider instance '" + instance + "' (iris) needs ai.provider." + instance
                    + ".baseUrl; without it the key would go to api.openai.com");
        }
    }

    @Override
    protected @Nullable DiscoveredModelInfo parseListingEntry(JsonNode entry) {
        return IrisModelListing.parse(entry);
    }

    @Override
    protected HttpClientBuilder httpClientBuilder(AiChatConfig config, String baseUrl) {
        return new IrisErrorHttpClientBuilder(super.httpClientBuilder(config, baseUrl));
    }
}
