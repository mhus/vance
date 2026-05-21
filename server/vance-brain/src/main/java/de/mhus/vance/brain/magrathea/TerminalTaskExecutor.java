package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Workflow terminus (plan §4.8). Returns a {@link TaskOutcome} whose
 * {@code outcome} encodes {@code success}/{@code failure} and whose
 * {@code output} carries the YAML's {@code result:} payload. The
 * {@link MagratheaTaskExecutor} dispatcher recognises {@code TERMINAL}
 * and writes the run's {@code StatusRecord} (DONE/FAILED) +
 * {@code ResultRecord} when the event flows through; this executor
 * never touches the journal itself.
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class TerminalTaskExecutor implements MagratheaTypeExecutor {

    private static final String SPEC_OUTCOME = "outcome";
    private static final String SPEC_RESULT  = "result";

    private final ObjectMapper objectMapper;

    @Override
    public MagratheaTaskType type() {
        return MagratheaTaskType.TERMINAL;
    }

    @Override
    public Optional<TaskOutcome> execute(MagratheaTaskContext context) {
        MagratheaStateSpec state = context.state();
        boolean success = isSuccess(state);
        JsonNode resultPayload = resultPayload(state);

        log.debug("Magrathea terminal '{}' for run {}: success={}",
                state.name(), context.workflowRunId(), success);

        if (success) {
            return Optional.of(TaskOutcome.successWith(resultPayload));
        }
        return Optional.of(new TaskOutcome(
                TaskCompletedEvent.OUTCOME_FAILURE,
                resultPayload,
                "terminal state '" + state.name() + "' marked failure",
                null));
    }

    private static boolean isSuccess(MagratheaStateSpec state) {
        String outcome = state.specString(SPEC_OUTCOME);
        if (outcome == null) return true;
        return !"failure".equalsIgnoreCase(outcome);
    }

    private JsonNode resultPayload(MagratheaStateSpec state) {
        Object raw = state.specField(SPEC_RESULT);
        if (raw == null) return null;
        return objectMapper.valueToTree(raw);
    }
}
