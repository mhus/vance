package de.mhus.vance.brain.ai;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Dispatches {@link AiChatConfig} to the matching {@link AiModelProvider} and
 * returns a fresh {@link AiChat}.
 *
 * <p>Providers are auto-discovered as Spring beans at startup and indexed by
 * {@link AiModelProvider#getName()}. Duplicate provider names fail fast.
 *
 * <p>Callers (typically Think-Engines) are responsible for resolving the
 * right config first — which model to use for a given scope, where to read
 * the API key from — then hand the built record in. This keeps the service
 * free of scope-cascade knowledge.
 */
@Service
@Slf4j
public class AiModelService {

    private final Map<String, AiModelProvider> providers;

    public AiModelService(List<AiModelProvider> providerBeans) {
        this.providers = providerBeans.stream().collect(
                Collectors.toMap(AiModelProvider::getName, p -> p, (a, b) -> {
                    throw new IllegalStateException(
                            "Duplicate AiModelProvider name: " + a.getName()
                                    + " — " + a.getClass() + " vs " + b.getClass());
                }));
        log.info("Registered AI providers: {}", providers.keySet());
    }

    /**
     * Build a chat for {@code config} using {@code options}.
     *
     * @throws AiChatException if no provider is registered for the name
     */
    public AiChat createChat(AiChatConfig config, AiChatOptions options) {
        AiModelProvider provider = providers.get(config.provider());
        if (provider == null) {
            throw new AiChatException(
                    "Unknown AI provider: " + config.provider()
                            + " — registered: " + providers.keySet());
        }
        return provider.createChat(config, options);
    }

    /** Convenience for callers who don't need custom options. */
    public AiChat createChat(AiChatConfig config) {
        return createChat(config, AiChatOptions.defaults());
    }

    /** Names of all registered providers, in no particular order. */
    public List<String> listProviders() {
        return List.copyOf(providers.keySet());
    }

    public boolean hasProvider(String name) {
        return providers.containsKey(name);
    }
}
