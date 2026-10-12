package de.mhus.vance.brain.ai.decision;

import de.mhus.vance.brain.ai.ProviderType;
import java.util.Optional;

/**
 * A provider plug-in for {@link AiDecisionService}. One Spring bean per
 * backend; {@link AiDecisionService} indexes them by
 * {@link #getInstanceName()} first and {@link #getType()} second.
 *
 * <p>One operation. Providers are stateless and thread-safe — a bulk
 * caller fans out over many threads against the same bean. They do not
 * validate questions against model limits, split oversized requests,
 * retry or throttle: all of that is {@code ZemService}'s job, so every
 * provider behaves the same under it.
 */
public interface AiDecisionModelProvider {

    /** Protocol type this provider serves when dispatched by type. */
    ProviderType getType();

    /**
     * Optional per-instance dispatch key, for an instance whose decision
     * wire differs from the protocol its chat wire declares — the
     * OpenRouter shape (chat: {@code openai}, decisions:
     * {@code /systemone}).
     */
    default Optional<String> getInstanceName() {
        return Optional.empty();
    }

    /**
     * Run one decision call.
     *
     * @throws AiDecisionException on any failure, with a
     *                             {@link AiDecisionException.Failure} class
     */
    DecisionResult decide(AiDecisionConfig config, DecisionRequest request);
}
