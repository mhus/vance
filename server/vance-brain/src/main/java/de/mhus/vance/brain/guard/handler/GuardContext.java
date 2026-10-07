package de.mhus.vance.brain.guard.handler;

import de.mhus.vance.brain.command.EngineCommand;
import de.mhus.vance.brain.recipe.GuardPoint;
import de.mhus.vance.brain.script.GuardScriptHost;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Point-scoped context handed to a {@link GuardHandler} hook — the
 * Java twin of the {@code vance.guard.*} script surface. Built by the
 * {@code ShootyGuardService} per run; the four action methods delegate
 * to the same point-constrained {@link GuardScriptHost} a script
 * gets, so cap-awareness, per-point availability and the re-entrancy
 * rules are identical for both guard shapes.
 *
 * @param process       the guarded process
 * @param point         the runtime point of this run — one of
 *                      {@link GuardPoint#START}, {@link GuardPoint#COMMAND},
 *                      {@link GuardPoint#STOP}, {@link GuardPoint#TERMINATE}
 *                      ({@link GuardPoint#BOTH} is a config alias, never a
 *                      runtime point)
 * @param task          the {@code vance.guard.task}: the turn's genuine
 *                      user input at the START point, else the process's
 *                      first user message (never {@code null}, may be empty)
 * @param output        the final output at the yield points; empty at
 *                      START/COMMAND
 * @param round         the process's current {@code guardRounds} counter
 *                      (meaningful at the yield points only)
 * @param maxRounds     this guard's {@code maxRounds} cap
 * @param naturalStop   whether the process stopped naturally (STOP
 *                      point) or was terminated (TERMINATE point)
 * @param command       the gated command at the COMMAND point;
 *                      {@code null} everywhere else
 * @param tool          the gated exec-run call at the TOOL point
 *                      ({@code exec_run} or a {@code work_}/{@code client_}
 *                      backend); {@code null} everywhere else
 * @param loopValues    the per-process loop scratch (wiped at each
 *                      genuine user turn) — the same store a script's
 *                      {@code vance.guard.loopValues} uses, so scripts
 *                      and handlers share flags
 * @param sessionValues the per-session scratch (survives the user-turn
 *                      reset) — likewise shared with scripts
 * @param actions       the point-constrained host backing the action
 *                      methods below
 */
public record GuardContext(
        ThinkProcessDocument process,
        GuardPoint point,
        String task,
        String output,
        int round,
        int maxRounds,
        boolean naturalStop,
        @Nullable EngineCommand command,
        @Nullable GuardToolCall tool,
        Map<String, Object> params,
        Map<String, Object> loopValues,
        Map<String, Object> sessionValues,
        GuardScriptHost actions) {

    public GuardContext {
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(params, "params");
        Objects.requireNonNull(loopValues, "loopValues");
        Objects.requireNonNull(sessionValues, "sessionValues");
        Objects.requireNonNull(actions, "actions");
    }

    /**
     * Inject {@code prompt} into the process's own pending queue and
     * schedule another engine turn (STOP/TERMINATE only). Cap-aware:
     * returns {@code false} once this guard's {@code maxRounds} is
     * reached.
     */
    public boolean continueWith(String prompt) {
        return actions.continueWith(prompt);
    }

    /** Veto the gated command or exec-run tool call (COMMAND/TOOL only); fail-closed by contract. */
    public boolean deny(String reason) {
        return actions.deny(reason);
    }

    /**
     * Activate {@code skillName} on the guarded process (any point;
     * sticky, no separate action turn). Runs with the re-entrancy
     * marker set — the activation sequence bypasses the COMMAND gate.
     */
    public boolean activateSkill(String skillName, @Nullable String args) {
        return actions.activateSkill(skillName, args);
    }

    /**
     * <b>Replace</b> this turn's system prompt with {@code text}
     * (START only, not additive, one text per turn).
     */
    public void setTurnPrompt(String text) {
        actions.setTurnPrompt(text);
    }
}
