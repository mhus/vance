package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.api.magrathea.MagratheaWorkflowSource;
import de.mhus.vance.shared.magrathea.MagratheaBoundsSpec;
import de.mhus.vance.shared.magrathea.MagratheaRetrySpec;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
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
        MagratheaStateSpec state = terminal(Map.of());

        Optional<TaskOutcome> outcome = executor.execute(ctx(state));

        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_SUCCESS);
        assertThat(outcome.get().output()).isNull();
    }

    @Test
    void outcome_failure_routes_to_failure_branch() {
        MagratheaStateSpec state = terminal(Map.of("outcome", "failure"));

        Optional<TaskOutcome> outcome = executor.execute(ctx(state));

        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_FAILURE);
        assertThat(outcome.get().errorMessage()).contains("marked failure");
    }

    @Test
    void result_block_is_serialised_into_output() {
        MagratheaStateSpec state = terminal(Map.of(
                "outcome", "success",
                "result", Map.of("summary", "merged", "merged_at", "2024-01-01T00:00:00Z")));

        Optional<TaskOutcome> outcome = executor.execute(ctx(state));

        assertThat(outcome.get().output()).isNotNull();
        assertThat(outcome.get().output().get("summary").asString()).isEqualTo("merged");
        assertThat(outcome.get().output().get("merged_at").asString()).isEqualTo("2024-01-01T00:00:00Z");
    }

    @Test
    void result_block_with_failure_outcome_still_emitted() {
        MagratheaStateSpec state = terminal(Map.of(
                "outcome", "failure",
                "result", Map.of("reason", "checks failed")));

        Optional<TaskOutcome> outcome = executor.execute(ctx(state));

        assertThat(outcome.get().output().get("reason").asString()).isEqualTo("checks failed");
        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_FAILURE);
    }

    private static MagratheaStateSpec terminal(Map<String, Object> spec) {
        return new MagratheaStateSpec(
                "done",
                MagratheaTaskType.TERMINAL,
                null, null, null,
                Map.of(), Map.of(),
                List.of(),
                MagratheaRetrySpec.none(),
                spec);
    }

    private static MagratheaTaskContext ctx(MagratheaStateSpec state) {
        return new MagratheaTaskContext(
                "acme", "proj", "r1", "task-1", "alice",
                new ResolvedMagratheaWorkflow("noop", "", MagratheaWorkflowSource.PROJECT,
                        null, null, null, null, "start",
                        Map.of(), Map.of(), MagratheaBoundsSpec.empty(), List.of(), List.of()),
                state, Map.of(), Map.of());
    }
}
