package de.mhus.vance.brain.ai.decision.systemone;

import de.mhus.vance.brain.ai.ProviderType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Local decision models through Ollama (≥ 0.35) on {@code /v1/systemone}
 * — e.g. {@code nimble}, {@code tev1}. No key. Ollama serves decision
 * calls one at a time (measured 2026-10-12), which is why the bundled
 * model docs default {@code maxConcurrentCalls} to 1.
 */
@Component
public class OllamaDecisionProvider extends SystemOneProviderSupport {

    public OllamaDecisionProvider(
            SystemOneWireClient wire, @Value("${vance.ai.ollama.base-url:http://localhost:11434}") String baseUrl) {
        super(wire, baseUrl, false);
    }

    @Override
    public ProviderType getType() {
        return ProviderType.OLLAMA;
    }
}
