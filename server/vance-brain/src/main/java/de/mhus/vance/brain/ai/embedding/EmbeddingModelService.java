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
        EmbeddingProvider provider = providers.get(
                config.provider() == null ? "" : config.provider().toLowerCase(Locale.ROOT));
        if (provider == null) {
            throw new IllegalArgumentException(
                    "Unknown embedding provider '" + config.provider()
                            + "' — known: " + providers.keySet());
        }
        return provider.createEmbeddingModel(config);
    }
}
