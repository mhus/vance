package de.mhus.vance.brain.vogon;

import de.mhus.vance.api.vogon.BranchAction;
import de.mhus.vance.api.vogon.PhaseSpec;
import de.mhus.vance.api.vogon.StrategySpec;
import de.mhus.vance.api.vogon.StrategyState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Executes the {@code do:} list of a Scorer-case (§2.5),
 * Decider-case (§2.6), Fork branch (§2.7) or Escalation rule (§2.8).
 * Pure StrategyState mutator — no Spring, no Mongo, no IO. The
 * surrounding engine consumes the returned {@link Result} and acts
 * on it (advance, pause, escalate, fail).
 *
 * <p>Action semantics follow {@code specification/vogon-engine.md}
 * §2.5 "Branch-Action-Vokabular". Terminal actions (Pause /
 * EscalateTo / JumpToPhase / ExitLoop / ExitStrategy) abort the
 * remaining list — strategy-load validation already rejects later
 * unreachable actions, but the executor enforces it at runtime as
 * a safety net.
 */
final class BranchActionExecutor {

    /** Outcome of a single {@code do:} list execution. */
    record Result(
            ResultKind kind,
            @Nullable String detail,
            BranchAction.@Nullable ExitOutcome exitOutcome,
            Map<String, Object> escalationParams) {

        static Result continueRunning() {
            return new Result(ResultKind.CONTINUE, null, null, Map.of());
        }

        static Result paused(@Nullable String reason) {
            return new Result(ResultKind.PAUSED, reason, null, Map.of());
        }

        static Result jumped(String phaseName) {
            return new Result(ResultKind.JUMPED, phaseName, null, Map.of());
        }

        static Result escalated(String strategy, Map<String, Object> params) {
            return new Result(
                    ResultKind.ESCALATED, strategy, null,
                    params == null ? Map.of() : params);
        }

        static Result exitedLoop(BranchAction.ExitOutcome outcome) {
            return new Result(ResultKind.EXIT_LOOP, null, outcome, Map.of());
        }

        static Result exitedStrategy(BranchAction.ExitOutcome outcome) {
            return new Result(ResultKind.EXIT_STRATEGY, null, outcome, Map.of());
        }
    }

    enum ResultKind {
        /** No terminal action fired — engine continues from where it was. */
        CONTINUE,
        /** {@code pause:} fired — engine sets process BLOCKED, waits for resume. */
        PAUSED,
        /** {@code jumpToPhase:} fired — {@code detail} is the phase name. */
        JUMPED,
        /** {@code escalateTo:} fired — {@code detail} is the sub-strategy name,
         *  {@code escalationParams} is the merged params map. */
        ESCALATED,
        /** {@code exitLoop:} fired. */
        EXIT_LOOP,
        /** {@code exitStrategy:} fired. */
        EXIT_STRATEGY
    }

    private BranchActionExecutor() {}

    /**
     * Run the actions in declared order. Returns the first terminal
     * action's effect (or {@link Result#continueRunning()} if none
     * fired). Strategy-state mutations from prior actions in the
     * same list survive (e.g. a {@code setFlag} before an
     * {@code escalateTo} stays in the flags map).
     */
    static Result execute(
            StrategySpec strategy,
            StrategyState state,
            List<BranchAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return Result.continueRunning();
        }
        for (BranchAction action : actions) {
            Result terminal = applyOne(strategy, state, action);
            if (terminal != null) return terminal;
        }
        return Result.continueRunning();
    }

    /** Returns non-null when {@code action} is terminal; null for
     *  flag-mutating non-terminals. */
    private static @Nullable Result applyOne(
            StrategySpec strategy, StrategyState state, BranchAction action) {
        if (action instanceof BranchAction.SetFlag sf) {
            state.getFlags().put(sf.name(), sf.value());
            return null;
        }
        if (action instanceof BranchAction.SetFlags sfs) {
            for (String name : sfs.names()) {
                state.getFlags().put(name, Boolean.TRUE);
            }
            return null;
        }
        if (action instanceof BranchAction.NotifyParent np) {
            // Notification is a side-channel emit — caller wires it to the
            // ProcessEventEmitter. We carry the request via a transient
            // flag so the engine can pick it up after execute() returns.
            // Contract: <storeAs>_pendingNotifyType / _pendingNotifySummary
            // — but for now we don't have an outer storeAs context. Use
            // a fixed transient key the engine drains.
            Map<String, Object> pending = new LinkedHashMap<>();
            pending.put("type", np.type());
            if (np.summary() != null) pending.put("summary", np.summary());
            state.getFlags().put("__pendingNotifyParent__", pending);
            return null;
        }
        if (action instanceof BranchAction.JumpToPhase jp) {
            String target = jp.phaseName();
            if (!phaseExists(strategy, target)) {
                throw new IllegalStateException(
                        "jumpToPhase: unknown phase '" + target + "'");
            }
            // Reset path to a single top-level segment. If the target is
            // a loop-phase the next runTurn enters it via resolveActivePhase.
            state.getCurrentPhasePath().clear();
            state.getCurrentPhasePath().add(target);
            return Result.jumped(target);
        }
        if (action instanceof BranchAction.Pause p) {
            return Result.paused(p.reason());
        }
        if (action instanceof BranchAction.EscalateTo et) {
            return Result.escalated(et.strategy(), et.params());
        }
        if (action instanceof BranchAction.ExitLoop el) {
            return Result.exitedLoop(el.outcome());
        }
        if (action instanceof BranchAction.ExitStrategy es) {
            return Result.exitedStrategy(es.outcome());
        }
        throw new IllegalStateException("Unknown branch action: " + action);
    }

    private static boolean phaseExists(StrategySpec strategy, String name) {
        for (PhaseSpec p : strategy.getPhases()) {
            if (p.getName().equals(name)) return true;
            if (p.getLoop() != null) {
                for (PhaseSpec sub : p.getLoop().getSubPhases()) {
                    if (sub.getName().equals(name)) return true;
                }
            }
        }
        return false;
    }
}
