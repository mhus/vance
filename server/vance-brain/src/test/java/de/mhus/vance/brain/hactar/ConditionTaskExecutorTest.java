package de.mhus.vance.brain.hactar;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.hactar.HactarTaskType;
import de.mhus.vance.shared.hactar.HactarBoundsSpec;
import de.mhus.vance.shared.hactar.HactarRetrySpec;
import de.mhus.vance.shared.hactar.HactarStateSpec;
import de.mhus.vance.shared.hactar.HactarTransition;
import de.mhus.vance.shared.hactar.ResolvedHactarWorkflow;
import de.mhus.vance.api.hactar.HactarWorkflowSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for {@link ConditionTaskExecutor}. The executor is
 * stateless aside from the {@link HactarExpressionEvaluator} it
 * delegates to.
 */
class ConditionTaskExecutorTest {

    private final HactarExpressionEvaluator eval = new HactarExpressionEvaluator();
    private final ConditionTaskExecutor executor = new ConditionTaskExecutor(eval);

    @Test
    void selects_first_matching_branch() {
        HactarStateSpec state = condition("route", List.of(
                new HactarTransition("#state['risk'] == 'low'", "merge"),
                new HactarTransition("#state['risk'] == 'high'", "review"),
                new HactarTransition(null, "fallback")));

        Optional<TaskOutcome> outcome = executor.execute(ctx(state, Map.of("risk", "low")));

        assertThat(outcome).isPresent();
        assertThat(outcome.get().nextStateOverride()).isEqualTo("merge");
        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_SUCCESS);
    }

    @Test
    void falls_through_to_else_branch_when_no_match() {
        HactarStateSpec state = condition("route", List.of(
                new HactarTransition("#state['risk'] == 'low'", "merge"),
                new HactarTransition(null, "fallback")));

        Optional<TaskOutcome> outcome = executor.execute(ctx(state, Map.of("risk", "medium")));

        assertThat(outcome.get().nextStateOverride()).isEqualTo("fallback");
    }

    @Test
    void fails_when_no_match_and_no_else_branch() {
        HactarStateSpec state = condition("route", List.of(
                new HactarTransition("#state['risk'] == 'low'", "merge")));

        Optional<TaskOutcome> outcome = executor.execute(ctx(state, Map.of("risk", "high")));

        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_FAILURE);
        assertThat(outcome.get().nextStateOverride()).isNull();
        assertThat(outcome.get().errorMessage()).contains("no matching branch");
    }

    @Test
    void condition_can_reference_params() {
        HactarStateSpec state = condition("gate", List.of(
                new HactarTransition("#params['tier'] == 'pro'", "pro_path"),
                new HactarTransition(null, "free_path")));

        Optional<TaskOutcome> outcome = executor.execute(new HactarTaskContext(
                "acme", "proj", "r1", "task-1", "alice",
                emptyWorkflow(), state,
                Map.of("tier", "pro"),
                Map.of()));

        assertThat(outcome.get().nextStateOverride()).isEqualTo("pro_path");
    }

    private static HactarStateSpec condition(String name, List<HactarTransition> transitions) {
        return new HactarStateSpec(
                name,
                HactarTaskType.CONDITION_TASK,
                null, null, null,
                Map.of(), Map.of(),
                transitions,
                HactarRetrySpec.none(),
                Map.of());
    }

    private static HactarTaskContext ctx(HactarStateSpec state, Map<String, Object> vars) {
        return new HactarTaskContext(
                "acme", "proj", "r1", "task-1", "alice",
                emptyWorkflow(), state,
                Map.of(),
                vars);
    }

    private static ResolvedHactarWorkflow emptyWorkflow() {
        return new ResolvedHactarWorkflow(
                "noop", "", HactarWorkflowSource.PROJECT,
                null, null, null, null, "start",
                Map.of(), Map.of(),
                HactarBoundsSpec.empty(),
                List.of(), List.of());
    }
}
