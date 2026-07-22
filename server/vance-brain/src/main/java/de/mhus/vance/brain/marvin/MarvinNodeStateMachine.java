package de.mhus.vance.brain.marvin;

import de.mhus.vance.api.marvin.ConcludeOutput;
import de.mhus.vance.api.marvin.NewTaskSpec;
import de.mhus.vance.api.marvin.PostChildrenAction;
import de.mhus.vance.api.marvin.PostChildrenOutput;
import de.mhus.vance.api.marvin.RecipeCall;
import de.mhus.vance.api.marvin.ReflectAction;
import de.mhus.vance.api.marvin.ReflectOutput;
import de.mhus.vance.api.marvin.ScopeAction;
import de.mhus.vance.api.marvin.ScopeOutput;
import de.mhus.vance.api.marvin.UserInputSpec;
import de.mhus.vance.api.marvin.ValidateOutput;
import de.mhus.vance.api.marvin.ValidateVerdict;
import de.mhus.vance.api.marvin.WorkerPhase;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Pure transition logic of the Marvin worker state-machine.
 * Takes the parsed phase output plus the in-progress iteration
 * counters and yields the next {@link Transition}.
 *
 * <p>No I/O, no Spring — fully unit-testable. The engine wraps
 * this with side effects (LLM calls, sub-process spawns, Mongo
 * writes). See {@code specification/marvin-engine.md} §3-§5.
 */
public final class MarvinNodeStateMachine {

    /** Default REFLECT iteration cap (recipe-overridable). */
    public static final int DEFAULT_REFLECT_MAX = 3;
    /** Default VALIDATE iteration cap (recipe-overridable). */
    public static final int DEFAULT_VALIDATE_MAX = 2;
    /** Default CONCLUDE retry cap (recipe-overridable). */
    public static final int DEFAULT_CONCLUDE_RETRIES = 2;
    /** Default NEED_MORE_DATA → REFLECT loop cap (recipe-overridable). */
    public static final int DEFAULT_NEED_MORE_DATA_MAX = 4;

    /** Caps for a particular Marvin process / recipe. */
    public record Caps(
            int reflectMax, int validateMax, int concludeRetries,
            int maxTreeDepth, int needMoreDataMax) {
        public static Caps defaults() {
            return new Caps(
                    DEFAULT_REFLECT_MAX,
                    DEFAULT_VALIDATE_MAX,
                    DEFAULT_CONCLUDE_RETRIES,
                    5,
                    DEFAULT_NEED_MORE_DATA_MAX);
        }
    }

    /** Mutable counters per-node — engine reads/writes these in
     *  the {@code MarvinNodeDocument}, transitions snapshot them
     *  here for testability.
     *
     *  <p>{@code needMoreDataIter} bounds the VALIDATE(NEED_MORE_DATA)
     *  → REFLECT → CONCLUDE → VALIDATE cycle. It has its own counter
     *  because that cycle does not increment {@code reflectIter} (REFLECT
     *  only bumps it when it emits a recipe call, not on
     *  PROCEED_TO_CONCLUDE) and {@code afterConclude} resets
     *  {@code validateIter} — without a dedicated bound the cycle can
     *  spin forever (code-review B3). */
    public record Counters(
            int reflectIter,
            int validateIter,
            int concludeRetries,
            int needMoreDataIter) {
        public Counters incReflect() {
            return new Counters(reflectIter + 1, validateIter, concludeRetries, needMoreDataIter);
        }
        public Counters incValidate() {
            return new Counters(reflectIter, validateIter + 1, concludeRetries, needMoreDataIter);
        }
        public Counters incConclude() {
            return new Counters(reflectIter, validateIter, concludeRetries + 1, needMoreDataIter);
        }
        public Counters incNeedMoreData() {
            return new Counters(reflectIter, validateIter, concludeRetries, needMoreDataIter + 1);
        }
        public static Counters initial() {
            return new Counters(0, 0, 0, 0);
        }
    }

    private MarvinNodeStateMachine() {}

    // ─────────────────── Transition results ───────────────────

    /** Marker for what the engine should do after a phase. */
    public sealed interface Transition permits
            ContinueWithPhase, CallRecipe, SpawnChildren,
            AskUserInput, FinishDone, FinishFailed {}

    /** Engine should run the named phase next on the same node. */
    public record ContinueWithPhase(
            WorkerPhase nextPhase,
            @Nullable String hintForNextPhase,
            Counters newCounters) implements Transition {}

    /** Engine should synchronously spawn the given recipe + steer,
     *  await reply, append it as USER msg, then drive REFLECT. */
    public record CallRecipe(
            RecipeCall call,
            Counters newCounters) implements Transition {}

    /** Engine should append the given children under this node and
     *  park the node. POST_CHILDREN will be triggered once they
     *  all terminate. */
    public record SpawnChildren(
            List<NewTaskSpec> children,
            Counters newCounters) implements Transition {}

    /** Engine should spawn a USER_INPUT child (sibling-after-anchor)
     *  and mark this node done with awaiting flag. */
    public record AskUserInput(
            UserInputSpec spec,
            Counters newCounters) implements Transition {}

    /** Node DONE — engine marks it, optionally with engine-side
     *  postActions to execute first. */
    public record FinishDone(
            String result,
            @Nullable List<de.mhus.vance.api.marvin.PostActionSpec> postActions,
            boolean validatorForced) implements Transition {}

    /** Node FAILED. */
    public record FinishFailed(String reason) implements Transition {}

    // ─────────────────── Transitions ───────────────────

    public static Transition afterScope(
            ScopeOutput out, Counters c, Caps caps) {
        return switch (out.action()) {
            case CALL_RECIPE -> {
                if (out.recipeCall() == null) {
                    yield new FinishFailed(
                            "SCOPE action=CALL_RECIPE but recipeCall is missing");
                }
                if (c.reflectIter() >= caps.reflectMax()) {
                    // shouldn't happen at SCOPE (always 0), defensive
                    yield new ContinueWithPhase(
                            WorkerPhase.CONCLUDE,
                            "reflect cap exhausted before any recipe call",
                            c);
                }
                yield new CallRecipe(out.recipeCall(), c.incReflect());
            }
            case PROCEED_TO_CONCLUDE ->
                    new ContinueWithPhase(WorkerPhase.CONCLUDE, null, c);
            case NEEDS_SUBTASKS -> {
                if (out.newTasks() == null || out.newTasks().isEmpty()) {
                    yield new FinishFailed(
                            "SCOPE action=NEEDS_SUBTASKS but newTasks is empty");
                }
                yield new SpawnChildren(out.newTasks(), c);
            }
            case NEEDS_USER_INPUT -> {
                if (out.userInput() == null) {
                    yield new FinishFailed(
                            "SCOPE action=NEEDS_USER_INPUT but userInput is missing");
                }
                yield new AskUserInput(out.userInput(), c);
            }
            case BLOCKED_BY_PROBLEM -> new FinishFailed(
                    out.problem() == null ? "blocked (no problem given)"
                            : out.problem());
        };
    }

    public static Transition afterReflect(
            ReflectOutput out, Counters c, Caps caps) {
        return switch (out.action()) {
            case CALL_RECIPE -> {
                if (out.recipeCall() == null) {
                    yield new FinishFailed(
                            "REFLECT action=CALL_RECIPE but recipeCall is missing");
                }
                if (c.reflectIter() >= caps.reflectMax()) {
                    yield new ContinueWithPhase(
                            WorkerPhase.CONCLUDE,
                            "reflect cap reached — forced PROCEED_TO_CONCLUDE",
                            c);
                }
                yield new CallRecipe(out.recipeCall(), c.incReflect());
            }
            case PROCEED_TO_CONCLUDE ->
                    new ContinueWithPhase(WorkerPhase.CONCLUDE, null, c);
            case NEEDS_SUBTASKS -> {
                if (out.newTasks() == null || out.newTasks().isEmpty()) {
                    yield new FinishFailed(
                            "REFLECT action=NEEDS_SUBTASKS but newTasks is empty");
                }
                yield new SpawnChildren(out.newTasks(), c);
            }
            case NEEDS_USER_INPUT -> {
                if (out.userInput() == null) {
                    yield new FinishFailed(
                            "REFLECT action=NEEDS_USER_INPUT but userInput is missing");
                }
                yield new AskUserInput(out.userInput(), c);
            }
            case BLOCKED_BY_PROBLEM -> new FinishFailed(
                    out.problem() == null ? "blocked (no problem given)"
                            : out.problem());
        };
    }

    public static Transition afterPostChildren(
            PostChildrenOutput out, Counters c, Caps caps,
            int currentTreeDepth) {
        return switch (out.action()) {
            case PROCEED_TO_CONCLUDE ->
                    new ContinueWithPhase(WorkerPhase.CONCLUDE, null, c);
            case NEEDS_SUBTASKS -> {
                if (out.newTasks() == null || out.newTasks().isEmpty()) {
                    yield new FinishFailed(
                            "POST_CHILDREN NEEDS_SUBTASKS but newTasks is empty");
                }
                if (currentTreeDepth >= caps.maxTreeDepth()) {
                    yield new ContinueWithPhase(
                            WorkerPhase.CONCLUDE,
                            "tree depth cap reached — forced PROCEED_TO_CONCLUDE",
                            c);
                }
                yield new SpawnChildren(out.newTasks(), c);
            }
            case BLOCKED_BY_PROBLEM -> new FinishFailed(
                    out.problem() == null ? "blocked after children"
                            : out.problem());
        };
    }

    /**
     * After CONCLUDE: always go to VALIDATE. Result is stashed by
     * the engine as the current candidate.
     */
    public static Transition afterConclude(
            ConcludeOutput out, Counters c) {
        // VALIDATE iteration counter resets each time we enter VALIDATE
        // from a fresh CONCLUDE — i.e. on CONCLUDE retry we start
        // VALIDATE counting at 0 again. concludeRetries tracks the
        // CONCLUDE-loop bound separately.
        Counters next = new Counters(
                c.reflectIter(), 0, c.concludeRetries(), c.needMoreDataIter());
        return new ContinueWithPhase(WorkerPhase.VALIDATE,
                /* result is in out.result(); engine carries it */
                null, next);
    }

    public static Transition afterValidate(
            ValidateOutput out, Counters c, Caps caps,
            String candidateResult,
            @Nullable List<de.mhus.vance.api.marvin.PostActionSpec> postActions) {
        ValidateVerdict v = out.verdict();
        return switch (v) {
            case PASS -> new FinishDone(candidateResult, postActions, false);
            case HARD_FAIL -> new FinishFailed(
                    out.reason() == null ? "validator HARD_FAIL"
                            : out.reason());
            case RETRY_CONCLUDE -> {
                if (c.concludeRetries() >= caps.concludeRetries()) {
                    // Cap exhausted — accept the last candidate.
                    yield new FinishDone(candidateResult, postActions, true);
                }
                yield new ContinueWithPhase(
                        WorkerPhase.CONCLUDE,
                        out.hint(),
                        c.incConclude());
            }
            case NEED_MORE_DATA -> {
                // Dedicated NEED_MORE_DATA bound: this cycle does not
                // increment reflectIter (REFLECT proceeds to CONCLUDE
                // without a recipe call) and afterConclude resets
                // validateIter, so reflectMax/validateMax never fire here
                // — without needMoreDataMax the loop spins forever (B3).
                if (c.needMoreDataIter() >= caps.needMoreDataMax()) {
                    // Cap exhausted — accept the last candidate.
                    yield new FinishDone(candidateResult, postActions, true);
                }
                yield new ContinueWithPhase(
                        WorkerPhase.REFLECT,
                        out.hint(),
                        c.incNeedMoreData());
            }
        };
    }

    /**
     * Called by the engine when VALIDATE itself hit its iteration
     * cap (2 calls) without a PASS or HARD_FAIL. Forces DONE on
     * the last candidate with an audit flag.
     */
    public static Transition validateCapExhausted(
            String candidateResult,
            @Nullable List<de.mhus.vance.api.marvin.PostActionSpec> postActions) {
        return new FinishDone(candidateResult, postActions, true);
    }
}
