package de.mhus.vance.brain.ai.embedding;

import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.ModelSize;
import de.mhus.vance.brain.ai.OutputTokenParam;
import de.mhus.vance.brain.ai.UsageGate;
import de.mhus.vance.shared.llmusage.CallAttribution;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Dispatches an {@link EmbeddingConfig} to the matching
 * {@link EmbeddingProvider}. Lookup is case-insensitive on the
 * provider name; unknown providers throw a clear error so the
 * caller doesn't ship a silent no-op.
 *
 * <p>A model built for a paying caller ({@link #createEmbeddingModel(EmbeddingConfig,
 * CallAttribution)}) asks the {@link UsageGate} before every call, like every
 * chat model does — embedding cost counts against the same budgets.
 */
@Service
@Slf4j
public class EmbeddingModelService {

    private final Map<String, EmbeddingProvider> providers;
    private final UsageGate usageGate;

    public EmbeddingModelService(List<EmbeddingProvider> providerBeans, UsageGate usageGate) {
        this.usageGate = usageGate;
        this.providers = providerBeans.stream()
                .collect(Collectors.toMap(p -> p.getName().toLowerCase(Locale.ROOT), p -> p, (a, b) -> {
                    throw new IllegalStateException("Duplicate embedding provider: " + a.getName());
                }));
        log.info("Registered embedding providers: {}", providers.keySet());
    }

    /**
     * Builds an {@link EmbeddingModel} for {@code config}, ungated. For a call
     * that is charged to someone use
     * {@link #createEmbeddingModel(EmbeddingConfig, CallAttribution)}.
     */
    public EmbeddingModel createEmbeddingModel(EmbeddingConfig config) {
        return lookup(config.provider()).createEmbeddingModel(config);
    }

    /**
     * Builds an {@link EmbeddingModel} for {@code config} whose every call is
     * first admitted by the {@link UsageGate} for {@code attribution} — the
     * tenant and project the embedding is charged to.
     */
    public EmbeddingModel createEmbeddingModel(EmbeddingConfig config, CallAttribution attribution) {
        return new BudgetGatedEmbeddingModel(
                createEmbeddingModel(config), attribution, embeddingModelInfo(config), usageGate);
    }

    /**
     * What the gate knows about an embedding model. Embedding models have no
     * catalog entry, so there is no rate either — the same snapshot the ledger
     * books them with ({@code RagService} writes embedding rows unpriced). The
     * gate admits a model without pricing; once embeddings carry a rate, it is
     * set here and the gate bites without any call-site change.
     */
    static ModelInfo embeddingModelInfo(EmbeddingConfig config) {
        return new ModelInfo(
                config.provider(),
                config.modelName(),
                /*contextWindowTokens*/ 0,
                /*defaultMaxOutputTokens*/ 0,
                ModelSize.SMALL,
                Set.of(),
                ModelInfo.DEFAULT_TIMEOUT_SECONDS,
                /*actionLoopCorrections*/ 0,
                /*stripThinkTags*/ false,
                /*messageParser*/ null,
                /*pricing*/ null,
                OutputTokenParam.MAX_TOKENS,
                Set.of(),
                /*reasoningEffortWhenOff*/ null,
                /*maxTools*/ null,
                /*mergeSystemMessages*/ false);
    }

    /**
     * Returns whether the provider with id {@code providerName} needs a
     * non-blank API key. Unknown providers default to {@code true} so
     * callers fail with a clear key-missing message rather than
     * silently treating typos as keyless.
     */
    public boolean requiresApiKey(String providerName) {
        EmbeddingProvider p = providers.get(providerName == null ? "" : providerName.toLowerCase(Locale.ROOT));
        return p == null || p.requiresApiKey();
    }

    private EmbeddingProvider lookup(String providerName) {
        EmbeddingProvider provider = providers.get(providerName == null ? "" : providerName.toLowerCase(Locale.ROOT));
        if (provider == null) {
            throw new IllegalArgumentException(
                    "Unknown embedding provider '" + providerName + "' — known: " + providers.keySet());
        }
        return provider;
    }
}
