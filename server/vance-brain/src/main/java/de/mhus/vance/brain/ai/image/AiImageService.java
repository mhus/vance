package de.mhus.vance.brain.ai.image;

import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.shared.document.ImageDestinationStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Dispatches an {@link AiImageConfig} to the matching
 * {@link AiImageModelProvider} and runs the generation against the
 * supplied {@link ImageDestinationStream}.
 *
 * <p><b>Dispatch order — instance before protocol.</b> Providers are
 * auto-discovered as Spring beans at startup and indexed by
 * {@link AiImageModelProvider#getType()}; duplicate provider types
 * fail fast. A provider may additionally declare a
 * {@link AiImageModelProvider#getInstanceName() named instance} — a
 * dispatch key that wins over the protocol type. That is the
 * OpenRouter shape: its <em>chat</em> wire is OpenAI (the
 * {@code openrouter} instance runs with {@code wireType: openai}), but
 * its <em>image</em> API is a dedicated, non-OpenAI endpoint — the
 * image adapter must be selected by the instance
 * ({@code config.providerInstance()}), not by the protocol. Instances
 * without a dedicated provider keep dispatching on the protocol type;
 * duplicate instance keys fail fast at startup, same as types.
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
    private Map<String, AiImageModelProvider> instanceProviders;

    @jakarta.annotation.PostConstruct
    public void postConstruct() {
        this.providers = providerBeans.stream()
                .filter(p -> p.getInstanceName().isEmpty())
                .collect(Collectors.toUnmodifiableMap(AiImageModelProvider::getType, p -> p, (a, b) -> {
                    throw new IllegalStateException("Duplicate AiImageModelProvider type: " + a.getType() + " — "
                            + a.getClass() + " vs " + b.getClass());
                }));
        this.instanceProviders = providerBeans.stream()
                .filter(p -> p.getInstanceName().isPresent())
                .collect(Collectors.toUnmodifiableMap(p -> p.getInstanceName().orElseThrow(), p -> p, (a, b) -> {
                    throw new IllegalStateException("Duplicate AiImageModelProvider instance: "
                            + a.getInstanceName().orElseThrow()
                            + " — " + a.getClass() + " vs " + b.getClass());
                }));
        log.info("Registered AI image providers: {} (instances: {})", providers.keySet(), instanceProviders.keySet());
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
    public void generate(AiImageConfig config, String prompt, ImageDestinationStream destination) {
        AiImageModelProvider provider = providerFor(config);
        if (provider == null) {
            throw new AiImageException("No image adapter for provider " + config.provider()
                    + (config.providerInstance().equals(config.provider())
                            ? ""
                            : " (instance " + config.providerInstance() + ")")
                    + " — registered: " + providers.keySet()
                    + (instanceProviders.isEmpty() ? "" : ", instances: " + instanceProviders.keySet()));
        }
        provider.generate(config, prompt, destination);
    }

    /**
     * Instance dispatch first, protocol dispatch second — see the
     * class doc for why the OpenRouter image adapter cannot hang off
     * the {@code openai} protocol key.
     */
    private @Nullable AiImageModelProvider providerFor(AiImageConfig config) {
        AiImageModelProvider byInstance = instanceProviders.get(config.providerInstance());
        if (byInstance != null) {
            return byInstance;
        }
        ProviderType type = ProviderType.requireWireName(config.provider());
        return providers.get(type);
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
