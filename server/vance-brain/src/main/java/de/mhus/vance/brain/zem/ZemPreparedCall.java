package de.mhus.vance.brain.zem;

import de.mhus.vance.api.zem.ZemQuestionDto;
import de.mhus.vance.brain.ai.decision.AiDecisionConfig;
import de.mhus.vance.brain.ai.decision.DecisionModelInfo;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Everything resolved once for a series of decisions: profile, model,
 * credentials, validated questions, limits. Immutable and thread-safe —
 * a bulk caller prepares once per run and calls
 * {@link ZemService#decide(ZemPreparedCall, Object, ZemUsageAccumulator)}
 * per record from many threads, without a settings or catalog lookup per
 * record.
 *
 * <p>Holds a decrypted API key: never log it, never persist it. Settings
 * changed after preparation take effect with the next preparation.
 *
 * @param alias         the alias the model was resolved from (metric tag)
 * @param profileName   recipe name, {@code null} for inline-only calls
 * @param maxConcurrent effective concurrency limit for the instance
 * @param rateLimitRps  effective rate limit for the instance
 */
public record ZemPreparedCall(
        String tenantId,
        @Nullable String projectId,
        @Nullable String processId,
        @Nullable String userId,
        @Nullable String profileName,
        String alias,
        AiDecisionConfig config,
        DecisionModelInfo model,
        List<ZemQuestionDto> questions,
        List<String> stateSelect,
        int maxRetries,
        @Nullable Integer maxConcurrent,
        @Nullable Double rateLimitRps) {

    public ZemPreparedCall {
        questions = List.copyOf(questions);
        stateSelect = List.copyOf(stateSelect);
    }

    /** Throttle key — instance credentials are tenant-scoped. */
    String throttleKey() {
        return tenantId + "/" + config.providerInstance();
    }

    @Override
    public String toString() {
        // The record holds a decrypted key in config — keep it out of logs.
        return "ZemPreparedCall[" + config.fullName() + ", profile=" + profileName + ", questions=" + questions.size()
                + "]";
    }
}
