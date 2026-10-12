package de.mhus.vance.brain.ai.decision;

import de.mhus.vance.api.zem.ZemQuestionDto;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One provider call: a state and the questions about it. Validation of
 * the questions against the model's limits is the service's job; this
 * record only guards the invariants every wire needs.
 *
 * @param state     string, map or list — System One rejects {@code null}
 * @param questions at least one, unique names, wire order = answer order
 */
public record DecisionRequest(Object state, List<ZemQuestionDto> questions) {

    public DecisionRequest {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        if (!(state instanceof String || state instanceof Map<?, ?> || state instanceof List<?>)) {
            throw new IllegalArgumentException("state must be a string, map or list, got "
                    + state.getClass().getSimpleName());
        }
        if (questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("at least one question is required");
        }
        questions = List.copyOf(questions);
        Set<String> names = new HashSet<>();
        for (ZemQuestionDto q : questions) {
            if (!names.add(q.getName())) {
                throw new IllegalArgumentException("duplicate question name '" + q.getName() + "'");
            }
        }
    }
}
