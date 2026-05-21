package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Sandbox + semantics of the SpEL-based condition evaluator (plan §6.5).
 */
class MagratheaExpressionEvaluatorTest {

    private final MagratheaExpressionEvaluator eval = new MagratheaExpressionEvaluator();

    @Test
    void equality_against_state_var_works() {
        Map<String, Object> vars = Map.of("plan_output", Map.of("risk", "low"));

        boolean result = eval.evaluateBoolean("#state['plan_output']['risk'] == 'low'",
                Map.of(), vars, Map.of());

        assertThat(result).isTrue();
    }

    @Test
    void boolean_combination_and_negation() {
        Map<String, Object> vars = Map.of("risk", "low", "tests_passed", true);

        assertThat(eval.evaluateBoolean(
                "#state['risk'] == 'low' && #state['tests_passed']",
                Map.of(), vars, Map.of())).isTrue();
        assertThat(eval.evaluateBoolean(
                "#state['risk'] == 'high' || !#state['tests_passed']",
                Map.of(), vars, Map.of())).isFalse();
    }

    @Test
    void in_operator_against_inline_list() {
        Map<String, Object> vars = Map.of("tier", "pro");

        assertThat(eval.evaluateBoolean(
                "#state['tier'] == 'free' || #state['tier'] == 'pro'",
                Map.of(), vars, Map.of())).isTrue();
    }

    @Test
    void matches_regex_operator() {
        Map<String, Object> vars = Map.of("category", "ERR-403");

        assertThat(eval.evaluateBoolean(
                "#state['category'] matches '^ERR-\\d+$'",
                Map.of(), vars, Map.of())).isTrue();
    }

    @Test
    void numeric_comparison() {
        Map<String, Object> vars = Map.of("count", 7);

        assertThat(eval.evaluateBoolean(
                "#state['count'] > 5 && #state['count'] < 10",
                Map.of(), vars, Map.of())).isTrue();
    }

    @Test
    void non_boolean_result_is_treated_as_false_with_warning() {
        Map<String, Object> vars = Map.of("name", "alice");

        assertThat(eval.evaluateBoolean("#state['name']", Map.of(), vars, Map.of()))
                .isFalse();
    }

    @Test
    void T_reference_is_blocked_by_sandbox() {
        assertThatThrownBy(() -> eval.evaluateBoolean(
                "T(java.lang.System).exit(0)", Map.of(), Map.of(), Map.of()))
                .isInstanceOf(MagratheaExpressionEvaluator.MagratheaExpressionException.class);
    }

    @Test
    void constructor_invocation_is_blocked_by_sandbox() {
        assertThatThrownBy(() -> eval.evaluateBoolean(
                "new java.lang.String('x') == 'x'", Map.of(), Map.of(), Map.of()))
                .isInstanceOf(MagratheaExpressionEvaluator.MagratheaExpressionException.class);
    }

    @Test
    void method_invocation_is_blocked_by_sandbox() {
        Map<String, Object> vars = Map.of("name", "alice");

        // .toUpperCase() goes through method resolution → empty resolvers blocks it.
        assertThatThrownBy(() -> eval.evaluateBoolean(
                "#state['name'].toUpperCase() == 'ALICE'", Map.of(), vars, Map.of()))
                .isInstanceOf(MagratheaExpressionEvaluator.MagratheaExpressionException.class);
    }

    @Test
    void params_and_tasks_variables_are_independent() {
        boolean result = eval.evaluateBoolean(
                "#params['tier'] == #tasks['plan']['output']['suggested_tier']",
                Map.of("tier", "pro"),
                Map.of(),
                Map.of("plan", Map.of("output", Map.of("suggested_tier", "pro"))));

        assertThat(result).isTrue();
    }

    @Test
    void missing_variable_yields_null_propagation() {
        // Reading a non-existent var key returns null in SpEL — comparison to null is well-defined.
        assertThat(eval.evaluateBoolean("#state['missing'] == null", Map.of(), Map.of(), Map.of()))
                .isTrue();
    }
}
