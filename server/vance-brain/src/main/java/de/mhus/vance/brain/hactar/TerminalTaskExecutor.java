package de.mhus.vance.brain.hactar;

import de.mhus.vance.api.hactar.HactarTaskType;
import de.mhus.vance.shared.hactar.HactarStateSpec;
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
 * {@link HactarTaskExecutor} dispatcher recognises {@code TERMINAL}
 * and writes the run's {@code StatusRecord} (DONE/FAILED) +
 * {@code ResultRecord} when the event flows through; this executor
 * never touches the journal itself.
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.hactar",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class TerminalTaskExecutor implements HactarTypeExecutor {

    private static final String SPEC_OUTCOME = "outcome";
    private static final String SPEC_RESULT  = "result";

    private final ObjectMapper objectMapper;

    @Override
    public HactarTaskType type() {
        return HactarTaskType.TERMINAL;
    }

    @Override
    public Optional<TaskOutcome> execute(HactarTaskContext context) {
        HactarStateSpec state = context.state();
        boolean success = isSuccess(state);
        JsonNode resultPayload = resultPayload(state);

        log.debug("Hactar terminal '{}' for run {}: success={}",
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

    private static boolean isSuccess(HactarStateSpec state) {
        String outcome = state.specString(SPEC_OUTCOME);
        if (outcome == null) return true;
        return !"failure".equalsIgnoreCase(outcome);
    }

    private JsonNode resultPayload(HactarStateSpec state) {
        Object raw = state.specField(SPEC_RESULT);
        if (raw == null) return null;
        return objectMapper.valueToTree(raw);
    }
}
