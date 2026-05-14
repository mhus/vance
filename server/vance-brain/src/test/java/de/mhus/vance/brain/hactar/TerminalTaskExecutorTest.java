package de.mhus.vance.brain.hactar;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.hactar.HactarTaskType;
import de.mhus.vance.api.hactar.HactarWorkflowSource;
import de.mhus.vance.shared.hactar.HactarBoundsSpec;
import de.mhus.vance.shared.hactar.HactarRetrySpec;
import de.mhus.vance.shared.hactar.HactarStateSpec;
import de.mhus.vance.shared.hactar.ResolvedHactarWorkflow;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class TerminalTaskExecutorTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final TerminalTaskExecutor executor = new TerminalTaskExecutor(objectMapper);

    @Test
    void default_outcome_is_success_when_unset() {
        HactarStateSpec state = terminal(Map.of());

        Optional<TaskOutcome> outcome = executor.execute(ctx(state));

        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_SUCCESS);
        assertThat(outcome.get().output()).isNull();
    }

    @Test
    void outcome_failure_routes_to_failure_branch() {
        HactarStateSpec state = terminal(Map.of("outcome", "failure"));

        Optional<TaskOutcome> outcome = executor.execute(ctx(state));

        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_FAILURE);
        assertThat(outcome.get().errorMessage()).contains("marked failure");
    }

    @Test
    void result_block_is_serialised_into_output() {
        HactarStateSpec state = terminal(Map.of(
                "outcome", "success",
                "result", Map.of("summary", "merged", "merged_at", "2024-01-01T00:00:00Z")));

        Optional<TaskOutcome> outcome = executor.execute(ctx(state));

        assertThat(outcome.get().output()).isNotNull();
        assertThat(outcome.get().output().get("summary").asString()).isEqualTo("merged");
        assertThat(outcome.get().output().get("merged_at").asString()).isEqualTo("2024-01-01T00:00:00Z");
    }

    @Test
    void result_block_with_failure_outcome_still_emitted() {
        HactarStateSpec state = terminal(Map.of(
                "outcome", "failure",
                "result", Map.of("reason", "checks failed")));

        Optional<TaskOutcome> outcome = executor.execute(ctx(state));

        assertThat(outcome.get().output().get("reason").asString()).isEqualTo("checks failed");
        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_FAILURE);
    }

    private static HactarStateSpec terminal(Map<String, Object> spec) {
        return new HactarStateSpec(
                "done",
                HactarTaskType.TERMINAL,
                null, null, null,
                Map.of(), Map.of(),
                List.of(),
                HactarRetrySpec.none(),
                spec);
    }

    private static HactarTaskContext ctx(HactarStateSpec state) {
        return new HactarTaskContext(
                "acme", "proj", "r1", "task-1", "alice",
                new ResolvedHactarWorkflow("noop", "", HactarWorkflowSource.PROJECT,
                        null, null, null, null, "start",
                        Map.of(), Map.of(), HactarBoundsSpec.empty(), List.of(), List.of()),
                state, Map.of(), Map.of());
    }
}
