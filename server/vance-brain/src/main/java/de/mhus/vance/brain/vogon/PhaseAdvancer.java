package de.mhus.vance.brain.vogon;

import de.mhus.vance.api.vogon.BranchAction;
import de.mhus.vance.api.vogon.GateSpec;
import de.mhus.vance.api.vogon.LoopSpec;
import de.mhus.vance.api.vogon.PhaseSpec;
import de.mhus.vance.api.vogon.StrategySpec;
import de.mhus.vance.api.vogon.StrategyState;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Pure path-stack advance logic for Vogon. Extracted from
 * {@link VogonEngine} so it can be unit-tested without booting Spring,
 * mocking services or running an LLM. The advancer mutates the
 * {@link StrategyState} but never persists; callers are responsible
 * for calling {@code persistState} after a returning method.
 *
 * <p>Two entry points:
 *
 * <ul>
 *   <li>{@link #resolveActivePhase} — call before each turn. If the
 *       leaf of {@code currentPhasePath} is a loop-phase (i.e. its
 *       {@link PhaseSpec#getLoop()} is non-null) the advancer pushes
 *       the loop's first sub-phase onto the path and bumps the loop
 *       counter to 1. Returns the resolved leaf phase.</li>
 *   <li>{@link #advanceAfter} — call after a phase reaches DONE.
 *       Mutates the state to reflect the next position and returns
 *       a {@link Outcome} describing what the engine should do next
 *       (run another phase, finalize, escalate).</li>
 * </ul>
 *
 * <p>Implements the spec described in
 * {@code specification/vogon-engine.md} §2.4 (Loop) and §6 (State).
 */
final class PhaseAdvancer {

    /** Separator inside qualified phase keys ({@code <loop>/<sub>}). */
    static final String QUALIFIED_KEY_SEP = "/";

    /** Suffix for the auto-managed loop-exhaustion flag. */
    static final String MAX_ITER_SUFFIX = "_max_iterations_reached";

    /** Suffix for the loop-failed flag (set by {@code EXIT_FAIL}). */
    static final String LOOP_FAILED_SUFFIX = "_failed";

    /** Decision returned by {@link #advanceAfter}. */
    enum Outcome {
        /** Path now points at the next phase to run. */
        CONTINUE,
        /** All phases done. Caller should set {@code strategyComplete}. */
        STRATEGY_DONE,
        /**
         * Loop hit {@code maxIterations} without satisfying its
         * {@code until} gate, and {@link LoopSpec.OnMaxReached#ESCALATE}
         * was the configured handler. Caller fires the
         * {@code loopExhausted} escalation trigger; falls back to
         * {@code notifyParent BLOCKED} when no rule matches.
         */
        ESCALATION_NEEDED,
        /**
         * Loop hit {@code maxIterations} with
         * {@link LoopSpec.OnMaxReached#EXIT_FAIL}. Caller marks the
         * Vogon process FAILED.
         */
        STRATEGY_FAILED
    }

    private PhaseAdvancer() {}

    // ──────────────────── resolve ────────────────────

    /**
     * Resolve the leaf of {@code state.currentPhasePath} to a runnable
     * phase. If the leaf is a loop-phase, push the loop's first
     * sub-phase onto the path and bump the loop counter to 1; returns
     * the sub-phase. Otherwise returns the leaf phase as-is.
     *
     * <p>Returns {@code null} when the path is empty (strategy is
     * already complete).
     */
    static @Nullable PhaseSpec resolveActivePhase(StrategySpec strategy, StrategyState state) {
        List<String> path = state.getCurrentPhasePath();
        if (path.isEmpty()) return null;
        PhaseSpec leaf = findOnPath(strategy, path);
        if (leaf == null) return null;
        if (leaf.getLoop() != null) {
            LoopSpec loop = leaf.getLoop();
            if (loop.getSubPhases().isEmpty()) {
                throw new IllegalStateException(
                        "Loop phase '" + leaf.getName() + "' has no subPhases");
            }
            PhaseSpec firstSub = loop.getSubPhases().get(0);
            path.add(firstSub.getName());
            state.getLoopCounters().put(leaf.getName(), 1);
            return firstSub;
        }
        return leaf;
    }

    // ──────────────────── advance ────────────────────

    /**
     * Mutate {@code state} to reflect that {@code done} just finished.
     * Top-level: append to phaseHistory, advance to next sibling or
     * mark strategy complete. Sub-phase: advance through the loop body
     * or evaluate the loop-exit gate. May recurse once when a loop
     * exits and the surrounding linear level needs to advance too.
     */
    static Outcome advanceAfter(
            StrategySpec strategy, StrategyState state, PhaseSpec done) {
        List<String> path = state.getCurrentPhasePath();
        if (path.size() > 1) {
            // Sub-phase inside a loop. Path = [<loopName>, <subName>].
            String loopName = path.get(0);
            PhaseSpec loopPhase = findTopLevel(strategy, loopName);
            if (loopPhase == null || loopPhase.getLoop() == null) {
                throw new IllegalStateException(
                        "currentPhasePath references unknown loop '" + loopName + "'");
            }
            state.getPhaseHistory().add(loopName + QUALIFIED_KEY_SEP + done.getName());
            return advanceInLoop(strategy, state, loopPhase, done);
        }
        // Top-level linear phase.
        state.getPhaseHistory().add(done.getName());
        int idx = topLevelIndexOf(strategy, done.getName());
        if (idx < 0 || idx + 1 >= strategy.getPhases().size()) {
            path.clear();
            state.setStrategyComplete(true);
            return Outcome.STRATEGY_DONE;
        }
        replaceLeaf(path, strategy.getPhases().get(idx + 1).getName());
        return Outcome.CONTINUE;
    }

    // ──────────────────── private — loop body ────────────────────

    private static Outcome advanceInLoop(
            StrategySpec strategy,
            StrategyState state,
            PhaseSpec loopPhase,
            PhaseSpec doneSub) {
        LoopSpec loop = loopPhase.getLoop();
        int subIdx = subIndexOf(loop, doneSub.getName());
        if (subIdx < 0) {
            throw new IllegalStateException(
                    "Sub-phase '" + doneSub.getName()
                            + "' not part of loop '" + loopPhase.getName() + "'");
        }
        if (subIdx + 1 < loop.getSubPhases().size()) {
            // More sub-phases in this iteration — advance.
            replaceLeaf(state.getCurrentPhasePath(),
                    loop.getSubPhases().get(subIdx + 1).getName());
            return Outcome.CONTINUE;
        }
        // Last sub-phase done → loop boundary.
        return evaluateLoopBoundary(strategy, state, loopPhase, loop);
    }

    private static Outcome evaluateLoopBoundary(
            StrategySpec strategy,
            StrategyState state,
            PhaseSpec loopPhase,
            LoopSpec loop) {
        String loopName = loopPhase.getName();
        if (gateSatisfied(loop.getUntil(), state)) {
            return exitLoop(strategy, state, loopPhase);
        }
        // Bump the iteration counter (we just finished the iteration).
        int counter = state.getLoopCounters().getOrDefault(loopName, 0);
        if (counter < loop.getMaxIterations()) {
            counter += 1;
            state.getLoopCounters().put(loopName, counter);
        }
        if (counter >= loop.getMaxIterations()) {
            state.getFlags().put(loopName + MAX_ITER_SUFFIX, true);
            // Re-evaluate gate now that the max-iter flag is set; it may
            // be part of a `requiresAny` and satisfy the gate.
            if (gateSatisfied(loop.getUntil(), state)) {
                return exitLoop(strategy, state, loopPhase);
            }
            return handleMaxReached(strategy, state, loopPhase, loop);
        }
        // Re-enter: invalidate loop body workers / artifacts and reset
        // the leaf to the first sub-phase.
        invalidateLoopBody(state, loopPhase, loop);
        replaceLeaf(state.getCurrentPhasePath(),
                loop.getSubPhases().get(0).getName());
        return Outcome.CONTINUE;
    }

    private static Outcome handleMaxReached(
            StrategySpec strategy,
            StrategyState state,
            PhaseSpec loopPhase,
            LoopSpec loop) {
        return switch (loop.getOnMaxReached()) {
            case EXIT_OK -> exitLoop(strategy, state, loopPhase);
            case EXIT_FAIL -> {
                state.getFlags().put(loopPhase.getName() + LOOP_FAILED_SUFFIX, true);
                yield exitLoop(strategy, state, loopPhase);
            }
            case ESCALATE -> {
                // The escalation block (§2.8) consumes the trigger; the
                // engine still has to leave the loop so it can fire the
                // escalation OR fall back to notifyParent BLOCKED.
                state.getFlags().put(loopPhase.getName() + LOOP_FAILED_SUFFIX, true);
                exitLoop(strategy, state, loopPhase);
                yield Outcome.ESCALATION_NEEDED;
            }
        };
    }

    /** Pop the loop level off the path and advance the outer linear
     *  level by recursing on {@link #advanceAfter}. */
    private static Outcome exitLoop(
            StrategySpec strategy, StrategyState state, PhaseSpec loopPhase) {
        List<String> path = state.getCurrentPhasePath();
        // Pop the sub-phase segment so the leaf becomes the loop name.
        path.remove(path.size() - 1);
        // Now treat the loop phase itself as "done" on the outer level.
        return advanceAfter(strategy, state, loopPhase);
    }

    /**
     * Force-leave the loop containing the current path leaf — invoked by
     * the engine when a Scorer/Decider {@code exitLoop} branch action
     * fires. {@code outcome=FAIL} sets {@code <loopName>_failed} before
     * popping. Returns the outer-level outcome from re-running
     * {@link #advanceAfter} on the loop phase.
     *
     * <p>Throws {@link IllegalStateException} when the path doesn't
     * point at a sub-phase (i.e. there's no loop to exit).
     */
    static Outcome forceExitLoop(
            StrategySpec strategy,
            StrategyState state,
            BranchAction.ExitOutcome outcome) {
        List<String> path = state.getCurrentPhasePath();
        if (path.size() < 2) {
            throw new IllegalStateException(
                    "exitLoop fired but current path is not inside a loop: " + path);
        }
        String loopName = path.get(0);
        PhaseSpec loopPhase = findTopLevel(strategy, loopName);
        if (loopPhase == null || loopPhase.getLoop() == null) {
            throw new IllegalStateException(
                    "exitLoop: outer segment '" + loopName + "' is not a loop phase");
        }
        if (outcome == BranchAction.ExitOutcome.FAIL) {
            state.getFlags().put(loopName + LOOP_FAILED_SUFFIX, true);
        }
        return exitLoop(strategy, state, loopPhase);
    }

    private static void invalidateLoopBody(
            StrategyState state, PhaseSpec loopPhase, LoopSpec loop) {
        Map<String, String> workers = state.getWorkerProcessIds();
        Map<String, Map<String, Object>> artifacts = state.getPhaseArtifacts();
        Map<String, Object> flags = state.getFlags();
        String prefix = loopPhase.getName() + QUALIFIED_KEY_SEP;
        // Drop worker handles, artifacts and per-phase flags so the
        // next iteration starts clean. Strategy-wide flags (the ones
        // the loop gate cares about) survive.
        workers.keySet().removeIf(k -> k.startsWith(prefix));
        artifacts.keySet().removeIf(k -> k.startsWith(prefix));
        for (PhaseSpec sub : loop.getSubPhases()) {
            flags.remove(sub.getName() + "_completed");
            flags.remove(sub.getName() + "_failed");
            flags.remove(sub.getName() + "_checkpointAnswered");
        }
    }

    // ──────────────────── helpers ────────────────────

    /** Resolve a path of the form {@code [linear]} or
     *  {@code [linear, sub]} to its leaf phase. */
    private static @Nullable PhaseSpec findOnPath(
            StrategySpec strategy, List<String> path) {
        if (path.isEmpty()) return null;
        PhaseSpec top = findTopLevel(strategy, path.get(0));
        if (top == null) return null;
        if (path.size() == 1) return top;
        if (top.getLoop() == null) return null;
        for (PhaseSpec sub : top.getLoop().getSubPhases()) {
            if (sub.getName().equals(path.get(1))) return sub;
        }
        return null;
    }

    private static @Nullable PhaseSpec findTopLevel(StrategySpec strategy, String name) {
        for (PhaseSpec p : strategy.getPhases()) {
            if (p.getName().equals(name)) return p;
        }
        return null;
    }

    private static int topLevelIndexOf(StrategySpec strategy, String name) {
        for (int i = 0; i < strategy.getPhases().size(); i++) {
            if (strategy.getPhases().get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    private static int subIndexOf(LoopSpec loop, String name) {
        for (int i = 0; i < loop.getSubPhases().size(); i++) {
            if (loop.getSubPhases().get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    private static void replaceLeaf(List<String> path, String newLeaf) {
        if (path.isEmpty()) {
            path.add(newLeaf);
        } else {
            path.set(path.size() - 1, newLeaf);
        }
    }

    static boolean gateSatisfied(@Nullable GateSpec gate, StrategyState state) {
        if (gate == null) return true;
        if (!gate.getRequires().isEmpty()) {
            for (String flag : gate.getRequires()) {
                if (!isFlagTrue(state, flag)) return false;
            }
        }
        if (!gate.getRequiresAny().isEmpty()) {
            boolean any = false;
            for (String flag : gate.getRequiresAny()) {
                if (isFlagTrue(state, flag)) { any = true; break; }
            }
            if (!any) return false;
        }
        return true;
    }

    private static boolean isFlagTrue(StrategyState state, String key) {
        Object v = state.getFlags().get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return v != null;
    }
}
