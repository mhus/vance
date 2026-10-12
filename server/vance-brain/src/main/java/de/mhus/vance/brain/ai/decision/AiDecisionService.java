package de.mhus.vance.brain.ai.decision;

import de.mhus.vance.brain.ai.ProviderType;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Dispatches an {@link AiDecisionConfig} to the matching
 * {@link AiDecisionModelProvider}.
 *
 * <p><b>Instance before protocol</b>, same shape and reason as
 * {@code AiAudioService}: OpenRouter declares {@code wireType: openai}
 * for chat, but its decision models live on a dedicated endpoint, so the
 * adapter is picked by the instance label. Duplicate keys fail at
 * startup.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AiDecisionService {

    private final List<AiDecisionModelProvider> providerBeans;
    private Map<ProviderType, AiDecisionModelProvider> providers = Map.of();
    private Map<String, AiDecisionModelProvider> instanceProviders = Map.of();

    @PostConstruct
    public void postConstruct() {
        this.providers = providerBeans.stream()
                .filter(p -> p.getInstanceName().isEmpty())
                .collect(Collectors.toUnmodifiableMap(AiDecisionModelProvider::getType, p -> p, (a, b) -> {
                    throw new IllegalStateException("Duplicate AiDecisionModelProvider type: " + a.getType() + " — "
                            + a.getClass() + " vs " + b.getClass());
                }));
        this.instanceProviders = providerBeans.stream()
                .filter(p -> p.getInstanceName().isPresent())
                .collect(Collectors.toUnmodifiableMap(p -> p.getInstanceName().orElseThrow(), p -> p, (a, b) -> {
                    throw new IllegalStateException("Duplicate AiDecisionModelProvider instance: "
                            + a.getInstanceName().orElseThrow() + " — " + a.getClass() + " vs " + b.getClass());
                }));
        log.info(
                "Registered AI decision providers: {} (instances: {})", providers.keySet(), instanceProviders.keySet());
    }

    /** Run one decision call. See {@link AiDecisionModelProvider#decide}. */
    public DecisionResult decide(AiDecisionConfig config, DecisionRequest request) {
        return providerFor(config).decide(config, request);
    }

    /** Whether a provider can serve this instance/protocol pair. */
    public boolean canServe(String providerInstance, String protocol) {
        if (instanceProviders.containsKey(providerInstance)) {
            return true;
        }
        Optional<ProviderType> type = ProviderType.fromWireName(protocol);
        return type.isPresent() && providers.containsKey(type.get());
    }

    private AiDecisionModelProvider providerFor(AiDecisionConfig config) {
        AiDecisionModelProvider byInstance = instanceProviders.get(config.providerInstance());
        if (byInstance != null) {
            return byInstance;
        }
        Optional<ProviderType> type = ProviderType.fromWireName(config.provider());
        AiDecisionModelProvider byType = type.map(providers::get).orElse(null);
        if (byType == null) {
            throw new AiDecisionException(
                    AiDecisionException.Failure.NO_ADAPTER,
                    "No decision adapter for provider " + config.provider()
                            + (config.providerInstance().equals(config.provider())
                                    ? ""
                                    : " (instance " + config.providerInstance() + ")")
                            + " — registered: " + providers.keySet()
                            + (instanceProviders.isEmpty() ? "" : ", instances: " + instanceProviders.keySet()));
        }
        return byType;
    }
}
