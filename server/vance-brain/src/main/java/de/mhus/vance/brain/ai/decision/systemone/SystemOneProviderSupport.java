package de.mhus.vance.brain.ai.decision.systemone;

import de.mhus.vance.brain.ai.decision.AiDecisionConfig;
import de.mhus.vance.brain.ai.decision.AiDecisionException;
import de.mhus.vance.brain.ai.decision.AiDecisionModelProvider;
import de.mhus.vance.brain.ai.decision.DecisionRequest;
import de.mhus.vance.brain.ai.decision.DecisionResult;
import java.net.URI;
import org.jspecify.annotations.Nullable;

/**
 * Shared shape of every System-One provider: resolve the endpoint from
 * the instance base-URL, attach the key, hand over to the
 * {@link SystemOneWireClient}. Subclasses only state their default
 * base-URL and whether a key is mandatory.
 *
 * <p><b>Endpoint rule:</b> a base-URL ending in {@code /v1} gets
 * {@code /systemone}, any other gets {@code /v1/systemone}. That one
 * rule covers the three base-URL conventions in use — OpenRouter's
 * instance setting ({@code https://openrouter.ai/api/v1}), Ollama's
 * sidecar ({@code http://localhost:11434}) and TypeSafe's SDK default
 * ({@code https://api.typesafe.ai}).
 */
abstract class SystemOneProviderSupport implements AiDecisionModelProvider {

    private static final String V1_SUFFIX = "/v1";
    private static final String ROUTE = "/systemone";

    private final SystemOneWireClient wire;
    private final String defaultBaseUrl;
    private final boolean keyRequired;

    SystemOneProviderSupport(SystemOneWireClient wire, String defaultBaseUrl, boolean keyRequired) {
        this.wire = wire;
        this.defaultBaseUrl = defaultBaseUrl;
        this.keyRequired = keyRequired;
    }

    @Override
    public DecisionResult decide(AiDecisionConfig config, DecisionRequest request) {
        return wire.post(endpoint(config.baseUrl()), key(config), config, request);
    }

    /** Endpoint for {@code baseUrl}, falling back to the provider default. */
    URI endpoint(@Nullable String baseUrl) {
        String base = baseUrl != null ? baseUrl.trim() : defaultBaseUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base.endsWith(V1_SUFFIX) ? base + ROUTE : base + V1_SUFFIX + ROUTE);
    }

    private @Nullable String key(AiDecisionConfig config) {
        if (keyRequired && config.apiKey() == null) {
            throw new AiDecisionException(
                    AiDecisionException.Failure.AUTH,
                    "No API key configured for " + config.fullName() + " — set ai.provider." + config.providerInstance()
                            + ".apiKey");
        }
        return config.apiKey();
    }
}
