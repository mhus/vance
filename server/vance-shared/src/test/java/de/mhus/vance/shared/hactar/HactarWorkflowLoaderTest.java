package de.mhus.vance.shared.hactar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.hactar.HactarErrorKind;
import de.mhus.vance.api.hactar.HactarTaskType;
import de.mhus.vance.api.hactar.HactarWorkflowSource;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic test for {@link HactarWorkflowLoader#validateYaml}. Bypasses
 * {@code DocumentService} via the public validate entry point — exercises
 * the parser end-to-end against a representative workflow body and
 * pinpoint each documented validation rule.
 */
class HactarWorkflowLoaderTest {

    private final HactarWorkflowLoader loader = new HactarWorkflowLoader(null);

    @Test
    void parses_full_workflow_with_all_task_types() {
        String yaml = """
                description: PR review workflow
                version: "1"
                start: plan
                bounds:
                  maxTotalCostUsd: 10.0
                  maxWallclockSeconds: 604800
                  maxTaskSpawns: 100
                allowedTools:
                  - web_search
                  - github.merge_pr
                tags:
                  - pr
                  - review
                parameters:
                  pr_url:
                    type: string
                    required: true
                  reviewer:
                    type: string
                    default: "@maintainers"
                states:
                  plan:
                    type: agent_task
                    recipe: jeltz
                    timeoutSeconds: 600
                    storeAs: plan_output
                    on:
                      success: run_checks
                      failure: escalate
                  run_checks:
                    type: script_task
                    run: npm test
                    retry:
                      maxAttempts: 2
                      on: [technical_error, timeout]
                      backoffSeconds: 60
                    on:
                      success: route_by_risk
                      business_error: escalate
                  route_by_risk:
                    type: condition_task
                    transitions:
                      - if: 'state.plan_output.risk == ''low'''
                        to: merge
                      - if: 'state.plan_output.risk == ''high'''
                        to: review
                      - else: review
                  review:
                    type: gate_task
                    inbox:
                      kind: APPROVAL
                      title: Approve PR?
                    on:
                      approved: merge
                      rejected: plan
                  merge:
                    type: tool_task
                    tool: github.merge_pr
                    on:
                      success: done
                  escalate:
                    type: gate_task
                    inbox:
                      kind: APPROVAL
                      title: Workflow blockiert
                    on:
                      approved: plan
                      rejected: done
                  done:
                    type: terminal
                    outcome: success
                """;

        ResolvedHactarWorkflow wf = loader.validateYaml("pr-review", yaml);

        assertThat(wf.name()).isEqualTo("pr-review");
        assertThat(wf.description()).isEqualTo("PR review workflow");
        assertThat(wf.version()).isEqualTo("1");
        assertThat(wf.source()).isEqualTo(HactarWorkflowSource.PROJECT);
        assertThat(wf.startState()).isEqualTo("plan");
        assertThat(wf.bounds().maxTotalCostUsd()).isEqualTo(10.0);
        assertThat(wf.bounds().maxWallclockSeconds()).isEqualTo(604800);
        assertThat(wf.bounds().maxTaskSpawns()).isEqualTo(100);
        assertThat(wf.allowedTools()).containsExactly("web_search", "github.merge_pr");
        assertThat(wf.tags()).containsExactly("pr", "review");
        assertThat(wf.parameters()).hasSize(2);
        assertThat(wf.parameters().get("pr_url").required()).isTrue();
        assertThat(wf.parameters().get("reviewer").defaultValue()).isEqualTo("@maintainers");

        HactarStateSpec plan = wf.states().get("plan");
        assertThat(plan.type()).isEqualTo(HactarTaskType.AGENT_TASK);
        assertThat(plan.timeoutSeconds()).isEqualTo(600);
        assertThat(plan.storeAs()).isEqualTo("plan_output");
        assertThat(plan.specString("recipe")).isEqualTo("jeltz");
        assertThat(plan.onOutcomes()).containsEntry("success", "run_checks");
        assertThat(plan.retry().maxAttempts()).isEqualTo(1);

        HactarStateSpec checks = wf.states().get("run_checks");
        assertThat(checks.type()).isEqualTo(HactarTaskType.SCRIPT_TASK);
        assertThat(checks.retry().maxAttempts()).isEqualTo(2);
        assertThat(checks.retry().onErrorKinds()).containsExactlyInAnyOrder(
                HactarErrorKind.TECHNICAL_ERROR, HactarErrorKind.TIMEOUT);
        assertThat(checks.retry().backoffSeconds()).isEqualTo(60);

        HactarStateSpec route = wf.states().get("route_by_risk");
        assertThat(route.type()).isEqualTo(HactarTaskType.CONDITION_TASK);
        assertThat(route.transitions()).hasSize(3);
        assertThat(route.transitions().get(0).target()).isEqualTo("merge");
        assertThat(route.transitions().get(2).isElse()).isTrue();

        HactarStateSpec review = wf.states().get("review");
        assertThat(review.type()).isEqualTo(HactarTaskType.GATE_TASK);
        assertThat(review.specField("inbox")).isNotNull();

        HactarStateSpec done = wf.states().get("done");
        assertThat(done.type()).isEqualTo(HactarTaskType.TERMINAL);
        assertThat(done.specString("outcome")).isEqualTo("success");
    }

    @Test
    void rejects_missing_start_field() {
        String yaml = """
                states:
                  any:
                    type: terminal
                """;
        assertThatThrownBy(() -> loader.validateYaml("x", yaml))
                .isInstanceOf(HactarWorkflowParseException.class)
                .hasMessageContaining("'start'");
    }

    @Test
    void rejects_start_pointing_to_unknown_state() {
        String yaml = """
                start: missing
                states:
                  other:
                    type: terminal
                """;
        assertThatThrownBy(() -> loader.validateYaml("x", yaml))
                .isInstanceOf(HactarWorkflowParseException.class)
                .hasMessageContaining("does not match any state");
    }

    @Test
    void rejects_transition_to_unknown_state() {
        String yaml = """
                start: a
                states:
                  a:
                    type: condition_task
                    on:
                      success: dangling
                """;
        assertThatThrownBy(() -> loader.validateYaml("x", yaml))
                .isInstanceOf(HactarWorkflowParseException.class)
                .hasMessageContaining("unknown state 'dangling'");
    }

    @Test
    void rejects_unknown_task_type() {
        String yaml = """
                start: a
                states:
                  a:
                    type: magic_task
                """;
        assertThatThrownBy(() -> loader.validateYaml("x", yaml))
                .isInstanceOf(HactarWorkflowParseException.class)
                .hasMessageContaining("unknown type");
    }

    @Test
    void rejects_transitions_outside_condition_task() {
        String yaml = """
                start: a
                states:
                  a:
                    type: tool_task
                    tool: noop
                    transitions:
                      - if: 'true'
                        to: a
                """;
        assertThatThrownBy(() -> loader.validateYaml("x", yaml))
                .isInstanceOf(HactarWorkflowParseException.class)
                .hasMessageContaining("only CONDITION_TASK");
    }

    @Test
    void rejects_entries_after_else_branch() {
        String yaml = """
                start: a
                states:
                  a:
                    type: condition_task
                    transitions:
                      - else: b
                      - if: 'true'
                        to: b
                  b:
                    type: terminal
                """;
        assertThatThrownBy(() -> loader.validateYaml("x", yaml))
                .isInstanceOf(HactarWorkflowParseException.class)
                .hasMessageContaining("after the 'else:' branch");
    }

    @Test
    void rejects_unknown_catch_error_kind() {
        String yaml = """
                start: a
                states:
                  a:
                    type: script_task
                    run: noop
                    catch:
                      mystery_error: a
                """;
        assertThatThrownBy(() -> loader.validateYaml("x", yaml))
                .isInstanceOf(HactarWorkflowParseException.class)
                .hasMessageContaining("not a known error kind");
    }

    @Test
    void rejects_empty_yaml() {
        assertThatThrownBy(() -> loader.validateYaml("x", ""))
                .isInstanceOf(HactarWorkflowParseException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void accepts_minimal_workflow_with_only_terminal() {
        String yaml = """
                start: end
                states:
                  end:
                    type: terminal
                """;
        ResolvedHactarWorkflow wf = loader.validateYaml("min", yaml);
        assertThat(wf.states()).hasSize(1);
        assertThat(wf.startState()).isEqualTo("end");
    }
}
