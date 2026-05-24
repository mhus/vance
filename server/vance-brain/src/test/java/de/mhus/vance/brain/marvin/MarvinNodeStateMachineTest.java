package de.mhus.vance.brain.marvin;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.marvin.ConcludeOutput;
import de.mhus.vance.api.marvin.NewTaskSpec;
import de.mhus.vance.api.marvin.PostChildrenAction;
import de.mhus.vance.api.marvin.PostChildrenOutput;
import de.mhus.vance.api.marvin.RecipeCall;
import de.mhus.vance.api.marvin.ReflectAction;
import de.mhus.vance.api.marvin.ReflectOutput;
import de.mhus.vance.api.marvin.ScopeAction;
import de.mhus.vance.api.marvin.ScopeOutput;
import de.mhus.vance.api.marvin.TaskKind;
import de.mhus.vance.api.marvin.UserInputSpec;
import de.mhus.vance.api.marvin.ValidateOutput;
import de.mhus.vance.api.marvin.ValidateVerdict;
import de.mhus.vance.api.marvin.WorkerPhase;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarvinNodeStateMachineTest {

    private static final MarvinNodeStateMachine.Caps CAPS =
            MarvinNodeStateMachine.Caps.defaults();
    private static final MarvinNodeStateMachine.Counters ZERO =
            MarvinNodeStateMachine.Counters.initial();

    // ─── SCOPE ───

    @Test
    void scope_proceedToConclude_goesToConclude() {
        ScopeOutput out = new ScopeOutput(
                ScopeAction.PROCEED_TO_CONCLUDE, null, null, null, null, "r");
        var t = MarvinNodeStateMachine.afterScope(out, ZERO, CAPS);
        assertThat(t).isInstanceOf(MarvinNodeStateMachine.ContinueWithPhase.class);
        assertThat(((MarvinNodeStateMachine.ContinueWithPhase) t).nextPhase())
                .isEqualTo(WorkerPhase.CONCLUDE);
    }

    @Test
    void scope_callRecipe_incrementsReflectIter() {
        ScopeOutput out = new ScopeOutput(
                ScopeAction.CALL_RECIPE,
                new RecipeCall("web-research", "go"),
                null, null, null, "r");
        var t = MarvinNodeStateMachine.afterScope(out, ZERO, CAPS);
        assertThat(t).isInstanceOf(MarvinNodeStateMachine.CallRecipe.class);
        var c = ((MarvinNodeStateMachine.CallRecipe) t);
        assertThat(c.call().recipe()).isEqualTo("web-research");
        assertThat(c.newCounters().reflectIter()).isEqualTo(1);
    }

    @Test
    void scope_needsSubtasks_yieldsSpawnChildren() {
        ScopeOutput out = new ScopeOutput(
                ScopeAction.NEEDS_SUBTASKS, null,
                List.of(new NewTaskSpec("g1", TaskKind.WORKER, null)),
                null, null, "r");
        var t = MarvinNodeStateMachine.afterScope(out, ZERO, CAPS);
        assertThat(t).isInstanceOf(MarvinNodeStateMachine.SpawnChildren.class);
    }

    @Test
    void scope_blockedByProblem_terminalFail() {
        ScopeOutput out = new ScopeOutput(
                ScopeAction.BLOCKED_BY_PROBLEM, null, null, null,
                "no path forward", "r");
        var t = MarvinNodeStateMachine.afterScope(out, ZERO, CAPS);
        assertThat(t).isInstanceOf(MarvinNodeStateMachine.FinishFailed.class);
        assertThat(((MarvinNodeStateMachine.FinishFailed) t).reason())
                .contains("no path forward");
    }

    @Test
    void scope_needsUserInput_yieldsAskUserInput() {
        UserInputSpec spec = new UserInputSpec(
                "DECISION", "Skill?", null, null, null);
        ScopeOutput out = new ScopeOutput(
                ScopeAction.NEEDS_USER_INPUT, null, null, spec, null, "r");
        var t = MarvinNodeStateMachine.afterScope(out, ZERO, CAPS);
        assertThat(t).isInstanceOf(MarvinNodeStateMachine.AskUserInput.class);
    }

    // ─── REFLECT ───

    @Test
    void reflect_callRecipe_atCap_forcedConclude() {
        var counters = new MarvinNodeStateMachine.Counters(3, 0, 0);
        ReflectOutput out = new ReflectOutput(
                ReflectAction.CALL_RECIPE,
                new RecipeCall("web-research", "x"),
                null, null, null, "r");
        var t = MarvinNodeStateMachine.afterReflect(out, counters, CAPS);
        assertThat(t).isInstanceOf(MarvinNodeStateMachine.ContinueWithPhase.class);
        assertThat(((MarvinNodeStateMachine.ContinueWithPhase) t).nextPhase())
                .isEqualTo(WorkerPhase.CONCLUDE);
    }

    // ─── POST_CHILDREN ───

    @Test
    void postChildren_needsSubtasks_atDepthCap_forcedConclude() {
        PostChildrenOutput out = new PostChildrenOutput(
                PostChildrenAction.NEEDS_SUBTASKS,
                List.of(new NewTaskSpec("g", TaskKind.WORKER, null)),
                null, "r");
        // Depth = 5, cap = 5 → forced
        var t = MarvinNodeStateMachine.afterPostChildren(out, ZERO, CAPS, 5);
        assertThat(t).isInstanceOf(MarvinNodeStateMachine.ContinueWithPhase.class);
        assertThat(((MarvinNodeStateMachine.ContinueWithPhase) t).nextPhase())
                .isEqualTo(WorkerPhase.CONCLUDE);
    }

    @Test
    void postChildren_needsSubtasks_withinDepth_spawnsChildren() {
        PostChildrenOutput out = new PostChildrenOutput(
                PostChildrenAction.NEEDS_SUBTASKS,
                List.of(new NewTaskSpec("g", TaskKind.WORKER, null)),
                null, "r");
        var t = MarvinNodeStateMachine.afterPostChildren(out, ZERO, CAPS, 2);
        assertThat(t).isInstanceOf(MarvinNodeStateMachine.SpawnChildren.class);
    }

    // ─── CONCLUDE ───

    @Test
    void conclude_alwaysGoesToValidate_resetsValidateIter() {
        ConcludeOutput out = new ConcludeOutput("# answer", null, "ok");
        var c = new MarvinNodeStateMachine.Counters(2, 1, 1);
        var t = MarvinNodeStateMachine.afterConclude(out, c);
        assertThat(t).isInstanceOf(MarvinNodeStateMachine.ContinueWithPhase.class);
        var cwp = (MarvinNodeStateMachine.ContinueWithPhase) t;
        assertThat(cwp.nextPhase()).isEqualTo(WorkerPhase.VALIDATE);
        assertThat(cwp.newCounters().validateIter()).isZero();
        assertThat(cwp.newCounters().concludeRetries()).isEqualTo(1);
    }

    // ─── VALIDATE ───

    @Test
    void validate_pass_finishesDone() {
        ValidateOutput out = new ValidateOutput(
                ValidateVerdict.PASS, null, null, "complete");
        var t = MarvinNodeStateMachine.afterValidate(
                out, ZERO, CAPS, "final result", null);
        assertThat(t).isInstanceOf(MarvinNodeStateMachine.FinishDone.class);
        var d = (MarvinNodeStateMachine.FinishDone) t;
        assertThat(d.result()).isEqualTo("final result");
        assertThat(d.validatorForced()).isFalse();
    }

    @Test
    void validate_hardFail_terminalFail() {
        ValidateOutput out = new ValidateOutput(
                ValidateVerdict.HARD_FAIL, null, null, "no way");
        var t = MarvinNodeStateMachine.afterValidate(
                out, ZERO, CAPS, "x", null);
        assertThat(t).isInstanceOf(MarvinNodeStateMachine.FinishFailed.class);
    }

    @Test
    void validate_retryConclude_belowCap_goesBack() {
        ValidateOutput out = new ValidateOutput(
                ValidateVerdict.RETRY_CONCLUDE, null, "redo intro", "weak");
        var t = MarvinNodeStateMachine.afterValidate(
                out, ZERO, CAPS, "x", null);
        assertThat(t).isInstanceOf(MarvinNodeStateMachine.ContinueWithPhase.class);
        var cwp = (MarvinNodeStateMachine.ContinueWithPhase) t;
        assertThat(cwp.nextPhase()).isEqualTo(WorkerPhase.CONCLUDE);
        assertThat(cwp.newCounters().concludeRetries()).isEqualTo(1);
    }

    @Test
    void validate_retryConclude_atCap_forcedDone() {
        var counters = new MarvinNodeStateMachine.Counters(0, 0, 2);
        ValidateOutput out = new ValidateOutput(
                ValidateVerdict.RETRY_CONCLUDE, null, "redo", "x");
        var t = MarvinNodeStateMachine.afterValidate(
                out, counters, CAPS, "lastCandidate", null);
        assertThat(t).isInstanceOf(MarvinNodeStateMachine.FinishDone.class);
        assertThat(((MarvinNodeStateMachine.FinishDone) t).validatorForced())
                .isTrue();
    }

    @Test
    void validate_needMoreData_atReflectCap_forcedDone() {
        var counters = new MarvinNodeStateMachine.Counters(3, 0, 0);
        ValidateOutput out = new ValidateOutput(
                ValidateVerdict.NEED_MORE_DATA, null, "get more", "x");
        var t = MarvinNodeStateMachine.afterValidate(
                out, counters, CAPS, "lastCandidate", null);
        assertThat(t).isInstanceOf(MarvinNodeStateMachine.FinishDone.class);
        assertThat(((MarvinNodeStateMachine.FinishDone) t).validatorForced())
                .isTrue();
    }

    @Test
    void validate_needMoreData_belowReflectCap_goesBackToReflect() {
        ValidateOutput out = new ValidateOutput(
                ValidateVerdict.NEED_MORE_DATA, null, "more", "x");
        var t = MarvinNodeStateMachine.afterValidate(
                out, ZERO, CAPS, "cand", null);
        assertThat(t).isInstanceOf(MarvinNodeStateMachine.ContinueWithPhase.class);
        assertThat(((MarvinNodeStateMachine.ContinueWithPhase) t).nextPhase())
                .isEqualTo(WorkerPhase.REFLECT);
    }

    @Test
    void validateCapExhausted_forcedDone() {
        var t = MarvinNodeStateMachine.validateCapExhausted("last", null);
        assertThat(t).isInstanceOf(MarvinNodeStateMachine.FinishDone.class);
        assertThat(((MarvinNodeStateMachine.FinishDone) t).validatorForced())
                .isTrue();
    }
}
