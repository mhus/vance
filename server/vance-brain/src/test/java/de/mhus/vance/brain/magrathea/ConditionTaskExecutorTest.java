package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.shared.magrathea.MagratheaBoundsSpec;
import de.mhus.vance.shared.magrathea.MagratheaRetrySpec;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.magrathea.MagratheaTransition;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import de.mhus.vance.api.magrathea.MagratheaWorkflowSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for {@link ConditionTaskExecutor}. The executor is
 * stateless aside from the {@link MagratheaExpressionEvaluator} it
 * delegates to.
 */
class ConditionTaskExecutorTest {

    private final MagratheaExpressionEvaluator eval = new MagratheaExpressionEvaluator();
    private final ConditionTaskExecutor executor = new ConditionTaskExecutor(eval);

    @Test
    void selects_first_matching_branch() {
        MagratheaStateSpec state = condition("route", List.of(
                new MagratheaTransition("#state['risk'] == 'low'", "merge"),
                new MagratheaTransition("#state['risk'] == 'high'", "review"),
                new MagratheaTransition(null, "fallback")));

        Optional<TaskOutcome> outcome = executor.execute(ctx(state, Map.of("risk", "low")));

        assertThat(outcome).isPresent();
        assertThat(outcome.get().nextStateOverride()).isEqualTo("merge");
        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_SUCCESS);
    }

    @Test
    void falls_through_to_else_branch_when_no_match() {
        MagratheaStateSpec state = condition("route", List.of(
                new MagratheaTransition("#state['risk'] == 'low'", "merge"),
                new MagratheaTransition(null, "fallback")));

        Optional<TaskOutcome> outcome = executor.execute(ctx(state, Map.of("risk", "medium")));

        assertThat(outcome.get().nextStateOverride()).isEqualTo("fallback");
    }

    @Test
    void fails_when_no_match_and_no_else_branch() {
        MagratheaStateSpec state = condition("route", List.of(
                new MagratheaTransition("#state['risk'] == 'low'", "merge")));

        Optional<TaskOutcome> outcome = executor.execute(ctx(state, Map.of("risk", "high")));

        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_FAILURE);
        assertThat(outcome.get().nextStateOverride()).isNull();
        assertThat(outcome.get().errorMessage()).contains("no matching branch");
    }

    @Test
    void condition_can_reference_params() {
        MagratheaStateSpec state = condition("gate", List.of(
                new MagratheaTransition("#params['tier'] == 'pro'", "pro_path"),
                new MagratheaTransition(null, "free_path")));

        Optional<TaskOutcome> outcome = executor.execute(new MagratheaTaskContext(
                "acme", "proj", "r1", "task-1", "alice",
                emptyWorkflow(), state,
                Map.of("tier", "pro"),
                Map.of()));

        assertThat(outcome.get().nextStateOverride()).isEqualTo("pro_path");
    }

    private static MagratheaStateSpec condition(String name, List<MagratheaTransition> transitions) {
        return new MagratheaStateSpec(
                name,
                MagratheaTaskType.CONDITION_TASK,
                null, null, null,
                Map.of(), Map.of(),
                transitions,
                MagratheaRetrySpec.none(),
                Map.of());
    }

    private static MagratheaTaskContext ctx(MagratheaStateSpec state, Map<String, Object> vars) {
        return new MagratheaTaskContext(
                "acme", "proj", "r1", "task-1", "alice",
                emptyWorkflow(), state,
                Map.of(),
                vars);
    }

    private static ResolvedMagratheaWorkflow emptyWorkflow() {
        return new ResolvedMagratheaWorkflow(
                "noop", "", MagratheaWorkflowSource.PROJECT,
                null, null, null, null, "start",
                Map.of(), Map.of(),
                MagratheaBoundsSpec.empty(),
                List.of(), List.of());
    }
}
