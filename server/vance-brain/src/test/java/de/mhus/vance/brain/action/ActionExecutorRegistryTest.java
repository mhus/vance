package de.mhus.vance.brain.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.action.ScriptSource;
import de.mhus.vance.api.action.TriggerAction;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ActionExecutorRegistryTest {

    private final TriggerContext ctx = TriggerContext.standalone(
            "t1", "p1", "alice", "corr-1", "scheduler:foo", null);

    // ──────────────────── Dispatch ────────────────────

    @Test
    void registry_dispatches_by_action_runtime_type() {
        ActionExecutorRegistry registry = new ActionExecutorRegistry(List.of(
                new StubRecipeExecutor(),
                new StubScriptExecutor(),
                new StubWorkflowExecutor()));

        ActionResult r = registry.execute(
                TriggerAction.Recipe.of("analyze", null, null, null),
                ctx,
                TriggerKind.SCHEDULER);

        assertThat(r.outcome()).isEqualTo(ActionOutcome.SCHEDULED);
        assertThat(r.spawnedId()).isEqualTo("recipe-spawned");
    }

    @Test
    void registry_dispatches_script_to_script_executor() {
        ActionExecutorRegistry registry = new ActionExecutorRegistry(List.of(
                new StubRecipeExecutor(),
                new StubScriptExecutor(),
                new StubWorkflowExecutor()));

        ActionResult r = registry.execute(
                new TriggerAction.Script(ScriptSource.DOCUMENT, null, "x.js", null, null, null),
                ctx,
                TriggerKind.SCHEDULER);

        assertThat(r.outcome()).isEqualTo(ActionOutcome.SUCCESS);
        assertThat(r.output()).containsEntry("source", "DOCUMENT");
    }

    @Test
    void registry_dispatches_workflow_to_workflow_executor() {
        ActionExecutorRegistry registry = new ActionExecutorRegistry(List.of(
                new StubRecipeExecutor(),
                new StubScriptExecutor(),
                new StubWorkflowExecutor()));

        ActionResult r = registry.execute(
                new TriggerAction.Workflow("pr-review", null, null),
                ctx,
                TriggerKind.EVENT);

        assertThat(r.outcome()).isEqualTo(ActionOutcome.SCHEDULED);
        assertThat(r.spawnedId()).isEqualTo("workflow-spawned");
    }

    // ──────────────────── Wiring errors ────────────────────

    @Test
    void duplicate_executor_for_same_type_rejected() {
        assertThatThrownBy(() -> new ActionExecutorRegistry(List.of(
                new StubRecipeExecutor(),
                new StubRecipeExecutor())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate ActionExecutor registration");
    }

    @Test
    void missing_executor_throws_on_dispatch() {
        ActionExecutorRegistry registry = new ActionExecutorRegistry(List.of(
                new StubRecipeExecutor())); // only Recipe registered

        assertThatThrownBy(() -> registry.execute(
                new TriggerAction.Workflow("w", null, null),
                ctx,
                TriggerKind.SCHEDULER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No ActionExecutor registered");
    }

    @Test
    void null_action_rejected() {
        ActionExecutorRegistry registry = new ActionExecutorRegistry(List.of());

        assertThatThrownBy(() -> registry.execute(null, ctx, TriggerKind.SCHEDULER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hasExecutorFor_reports_registration_state() {
        ActionExecutorRegistry registry = new ActionExecutorRegistry(List.of(
                new StubRecipeExecutor()));

        assertThat(registry.hasExecutorFor(TriggerAction.Recipe.class)).isTrue();
        assertThat(registry.hasExecutorFor(TriggerAction.Workflow.class)).isFalse();
    }

    @Test
    void empty_executor_list_is_valid_but_dispatch_fails() {
        ActionExecutorRegistry registry = new ActionExecutorRegistry(List.of());

        assertThat(registry.hasExecutorFor(TriggerAction.Recipe.class)).isFalse();
        assertThatThrownBy(() -> registry.execute(
                TriggerAction.Recipe.of("r", null, null, null),
                ctx,
                TriggerKind.SCHEDULER))
                .isInstanceOf(IllegalStateException.class);
    }

    // ──────────────────── Stub executors ────────────────────

    private static final class StubRecipeExecutor implements ActionExecutor<TriggerAction.Recipe> {
        @Override public Class<TriggerAction.Recipe> actionType() {
            return TriggerAction.Recipe.class;
        }
        @Override public ActionResult execute(ActionInvocation<TriggerAction.Recipe> invocation) {
            return ActionResult.scheduled("recipe-spawned");
        }
    }

    private static final class StubScriptExecutor implements ActionExecutor<TriggerAction.Script> {
        @Override public Class<TriggerAction.Script> actionType() {
            return TriggerAction.Script.class;
        }
        @Override public ActionResult execute(ActionInvocation<TriggerAction.Script> invocation) {
            return ActionResult.success(Map.of(
                    "source", invocation.action().source().name(),
                    "path", invocation.action().path()));
        }
    }

    private static final class StubWorkflowExecutor implements ActionExecutor<TriggerAction.Workflow> {
        @Override public Class<TriggerAction.Workflow> actionType() {
            return TriggerAction.Workflow.class;
        }
        @Override public ActionResult execute(ActionInvocation<TriggerAction.Workflow> invocation) {
            return ActionResult.scheduled("workflow-spawned");
        }
    }
}
