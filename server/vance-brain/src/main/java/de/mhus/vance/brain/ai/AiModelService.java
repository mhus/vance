package de.mhus.vance.brain.ai;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Dispatches {@link AiChatConfig} to the matching {@link AiModelProvider} and
 * returns a fresh {@link AiChat}.
 *
 * <p>Providers are auto-discovered as Spring beans at startup and indexed by
 * {@link AiModelProvider#getType()}. Duplicate provider types fail fast.
 *
 * <p>Callers (typically Think-Engines) are responsible for resolving the
 * right config first — which model to use for a given scope, where to read
 * the API key from — then hand the built record in. This keeps the service
 * free of scope-cascade knowledge.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AiModelService {

    private final List<AiModelProvider> providerBeans;
    private Map<ProviderType, AiModelProvider> providers;

    @jakarta.annotation.PostConstruct
    public void postConstruct() {
        this.providers = providerBeans.stream().collect(
                Collectors.toUnmodifiableMap(AiModelProvider::getType, p -> p, (a, b) -> {
                    throw new IllegalStateException(
                            "Duplicate AiModelProvider type: " + a.getType()
                                    + " — " + a.getClass() + " vs " + b.getClass());
                }));
        log.info("Registered AI providers: {}", providers.keySet());
    }

    /**
     * Build a chat for {@code config} using {@code options}.
     *
     * @throws AiChatException if no provider is registered for the type
     * @throws IllegalArgumentException if the wire-name in {@code config}
     *         maps to no known {@link ProviderType}
     */
    public AiChat createChat(AiChatConfig config, AiChatOptions options) {
        ProviderType type = ProviderType.requireWireName(config.provider());
        AiModelProvider provider = providers.get(type);
        if (provider == null) {
            throw new AiChatException(
                    "No adapter for provider " + type
                            + " — registered: " + providers.keySet());
        }
        return provider.createChat(config, options);
    }

    /** Convenience for callers who don't need custom options. */
    public AiChat createChat(AiChatConfig config) {
        return createChat(config, AiChatOptions.defaults());
    }

    /**
     * Build a chat from a {@link ChatBehavior} — single entry behaves
     * exactly like {@link #createChat(AiChatConfig, AiChatOptions)}.
     * Multi-entry composes the entries into a {@link ChainedAiChat} that
     * advances through fallbacks when an entry's retry budget is exhausted.
     */
    public AiChat createChat(ChatBehavior behavior, AiChatOptions options) {
        if (behavior.entries().size() == 1) {
            return createChat(behavior.entries().get(0).config(), options);
        }
        List<AiChat> built = behavior.entries().stream()
                .map(e -> createChat(e.config(), options))
                .toList();
        String name = behavior.entries().stream()
                .map(ChatBehavior.Entry::label)
                .reduce((a, b) -> a + "+" + b)
                .orElse("chained");
        log.debug("ChatBehavior chain built: {} ({} entries)", name, built.size());
        return new ChainedAiChat(name, built);
    }

    /** Wire-names of all registered providers, in no particular order. */
    public List<String> listProviders() {
        return providers.keySet().stream().map(ProviderType::wireName).toList();
    }

    /** Typed lookup. Preferred for new call sites. */
    public boolean hasProvider(ProviderType type) {
        return providers.containsKey(type);
    }

    /** Wire-name lookup. Returns {@code false} for unknown wire-names. */
    public boolean hasProvider(String name) {
        Optional<ProviderType> type = ProviderType.fromWireName(name);
        return type.isPresent() && providers.containsKey(type.get());
    }

    /**
     * Typed lookup of a registered provider — used by the model-discovery
     * job to invoke {@link AiModelProvider#listAvailableModels} on the
     * adapter matching a tenant's {@code ai.provider.<instance>.type}
     * setting. Returns empty when nothing is registered for the type.
     */
    public Optional<AiModelProvider> findProvider(ProviderType type) {
        return Optional.ofNullable(providers.get(type));
    }
}
