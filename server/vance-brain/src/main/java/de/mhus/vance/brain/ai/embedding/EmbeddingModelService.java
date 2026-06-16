package de.mhus.vance.brain.ai.embedding;

import dev.langchain4j.model.embedding.EmbeddingModel;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Dispatches an {@link EmbeddingConfig} to the matching
 * {@link EmbeddingProvider}. Lookup is case-insensitive on the
 * provider name; unknown providers throw a clear error so the
 * caller doesn't ship a silent no-op.
 */
@Service
@Slf4j
public class EmbeddingModelService {

    private final Map<String, EmbeddingProvider> providers;

    public EmbeddingModelService(List<EmbeddingProvider> providerBeans) {
        this.providers = providerBeans.stream().collect(Collectors.toMap(
                p -> p.getName().toLowerCase(Locale.ROOT),
                p -> p,
                (a, b) -> {
                    throw new IllegalStateException(
                            "Duplicate embedding provider: " + a.getName());
                }));
        log.info("Registered embedding providers: {}", providers.keySet());
    }

    /** Builds an {@link EmbeddingModel} for {@code config}. */
    public EmbeddingModel createEmbeddingModel(EmbeddingConfig config) {
        return lookup(config.provider()).createEmbeddingModel(config);
    }

    /**
     * Returns whether the provider with id {@code providerName} needs a
     * non-blank API key. Unknown providers default to {@code true} so
     * callers fail with a clear key-missing message rather than
     * silently treating typos as keyless.
     */
    public boolean requiresApiKey(String providerName) {
        EmbeddingProvider p = providers.get(
                providerName == null ? "" : providerName.toLowerCase(Locale.ROOT));
        return p == null || p.requiresApiKey();
    }

    private EmbeddingProvider lookup(String providerName) {
        EmbeddingProvider provider = providers.get(
                providerName == null ? "" : providerName.toLowerCase(Locale.ROOT));
        if (provider == null) {
            throw new IllegalArgumentException(
                    "Unknown embedding provider '" + providerName
                            + "' — known: " + providers.keySet());
        }
        return provider;
    }
}
