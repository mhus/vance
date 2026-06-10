package de.mhus.vance.brain.ai.image;

import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.shared.document.ImageDestinationStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Dispatches an {@link AiImageConfig} to the matching
 * {@link AiImageModelProvider} and runs the generation against the
 * supplied {@link ImageDestinationStream}.
 *
 * <p>Providers are auto-discovered as Spring beans at startup and indexed by
 * {@link AiImageModelProvider#getType()}. Duplicate provider types fail fast.
 *
 * <p>Callers (typically the Fenchurch service) resolve the right config
 * first — which model is "default:image" for this scope, where to read
 * the API key from — then hand the built record in. This keeps the
 * service free of scope-cascade knowledge.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AiImageService {

    private final List<AiImageModelProvider> providerBeans;
    private Map<ProviderType, AiImageModelProvider> providers;

    @jakarta.annotation.PostConstruct
    public void postConstruct() {
        this.providers = providerBeans.stream().collect(
                Collectors.toUnmodifiableMap(AiImageModelProvider::getType, p -> p, (a, b) -> {
                    throw new IllegalStateException(
                            "Duplicate AiImageModelProvider type: " + a.getType()
                                    + " — " + a.getClass() + " vs " + b.getClass());
                }));
        log.info("Registered AI image providers: {}", providers.keySet());
    }

    /**
     * Generate one image for {@code prompt} using {@code config} and
     * stream the result through {@code destination}.
     *
     * @throws AiImageException if no provider is registered for the
     *                         resolved type, or the provider call fails
     * @throws IllegalArgumentException if the wire-name in {@code config}
     *                         maps to no known {@link ProviderType}
     */
    public void generate(AiImageConfig config, String prompt,
                         ImageDestinationStream destination) {
        ProviderType type = ProviderType.requireWireName(config.provider());
        AiImageModelProvider provider = providers.get(type);
        if (provider == null) {
            throw new AiImageException(
                    "No image adapter for provider " + type
                            + " — registered: " + providers.keySet());
        }
        provider.generate(config, prompt, destination);
    }

    /** Wire-names of all registered image providers, in no particular order. */
    public List<String> listProviders() {
        return providers.keySet().stream().map(ProviderType::wireName).toList();
    }

    /** Typed lookup. */
    public boolean hasProvider(ProviderType type) {
        return providers.containsKey(type);
    }

    /** Wire-name lookup. Returns {@code false} for unknown wire-names. */
    public boolean hasProvider(String name) {
        Optional<ProviderType> type = ProviderType.fromWireName(name);
        return type.isPresent() && providers.containsKey(type.get());
    }
}
