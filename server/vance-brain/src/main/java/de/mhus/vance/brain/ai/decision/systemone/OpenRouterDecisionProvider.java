package de.mhus.vance.brain.ai.decision.systemone;

import de.mhus.vance.brain.ai.ProviderType;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decision models via the {@code openrouter} instance. OpenRouter passes
 * the System-One schema through unchanged on {@code /api/v1/systemone}
 * (identical to its {@code /api/alpha/decisions} route, measured
 * 2026-10-12) and adds {@code usage.cost} and a generation {@code id}.
 * Selected by instance name — the instance declares {@code openai} for
 * chat, and dispatching on that type would steal OpenAI-proper calls.
 */
@Component
public class OpenRouterDecisionProvider extends SystemOneProviderSupport {

    /** Dispatch key — matches the bundled sidecar's instance name. */
    static final String INSTANCE_NAME = "openrouter";

    private static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";

    public OpenRouterDecisionProvider(
            SystemOneWireClient wire, @Value("${vance.ai.openrouter.base-url:}") String baseUrl) {
        super(wire, baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim(), true);
    }

    @Override
    public ProviderType getType() {
        return ProviderType.OPENAI;
    }

    @Override
    public Optional<String> getInstanceName() {
        return Optional.of(INSTANCE_NAME);
    }
}
