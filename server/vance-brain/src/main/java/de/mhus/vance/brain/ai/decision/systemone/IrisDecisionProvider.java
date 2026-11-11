package de.mhus.vance.brain.ai.decision.systemone;

import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ai.decision.AiDecisionConfig;
import de.mhus.vance.brain.ai.decision.AiDecisionException;
import de.mhus.vance.brain.ai.decision.DecisionRequest;
import de.mhus.vance.brain.ai.decision.DecisionResult;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Decision models through Iris, which serves the System-One wire at
 * {@code /v1/systemone}. Selected by the {@code iris} protocol; no base-URL
 * fallback, and the vendor cost is not reported (see
 * {@link de.mhus.vance.brain.ai.iris.IrisImageProvider} for why).
 */
@Component
public class IrisDecisionProvider extends SystemOneProviderSupport {

    public IrisDecisionProvider(SystemOneWireClient wire) {
        super(wire, "", true);
    }

    @Override
    public ProviderType getType() {
        return ProviderType.IRIS;
    }

    @Override
    public Optional<String> getInstanceName() {
        return Optional.empty();
    }

    @Override
    String baseUrlFor(@Nullable String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new AiDecisionException(
                    AiDecisionException.Failure.INVALID_REQUEST,
                    "The iris decision provider needs ai.provider.<instance>.baseUrl");
        }
        return baseUrl.trim();
    }

    @Override
    public DecisionResult decide(AiDecisionConfig config, DecisionRequest request) {
        DecisionResult result = super.decide(config, request);
        return new DecisionResult(
                result.model(),
                result.answers(),
                result.inputTokens(),
                result.outputTokens(),
                null,
                result.requestId());
    }
}
