package de.mhus.vance.brain.thinkengine.action;

import de.mhus.vance.api.thinkprocess.ProcessMode;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * Static helpers shared by every engine that wires
 * {@link ActionLoopJudgeService} into its action-loop fallback path.
 * Pulled out of {@code ArthurEngine} once a second consumer ({@code
 * EddieEngine}) needed the same logic; further engines should reuse
 * these rather than re-derive.
 */
public final class ActionLoopJudgeHelpers {

    /**
     * Iteration budget granted on each judge-approved extension. Kept a
     * fraction of the typical initial {@code maxIters} — roughly half — so
     * the judge re-evaluates the loop's health after a short round rather
     * than handing out a full fresh budget each time.
     *
     * <p>Raised 6 -> 10 on 2026-08-06 to hold that ratio. It was half of
     * the then-current Arthur budget of 12; once the budget moved to 20 the
     * unchanged 6 had quietly become under a third, which is a tighter
     * supervision cadence than anyone chose — and every extra round is an
     * extra judge LLM call on top of the turn.
     *
     * <p>There is deliberately <b>no</b> absolute extension ceiling: as
     * long as the judge keeps deciding {@code extend} (i.e. it still
     * judges the loop healthy / progressing) the engine keeps granting
     * these rounds without end. The bound on a genuinely stuck run is
     * the judge flipping to {@code synthesize}; the bounds on a runaway
     * are the user pressing ESC / {@code /pause} (honoured mid-loop by
     * {@link StructuredActionEngine#runStructuredActionLoop}) and the
     * per-turn wallclock safety net.
     *
     * <p>Shared by Arthur and Eddie; both sit at an initial budget of 20,
     * so the half-the-base ratio holds for either engine.
     */
    public static final int JUDGE_EXTENSION_ITERS = 10;

    /**
     * Cap on the number of tool-call entries shipped to the judge —
     * older calls drop off the front of the list. Keeps the judge
     * prompt cheap even when the action-loop ran for many iterations.
     */
    public static final int JUDGE_TOOLS_USED_MAX = 30;

    private ActionLoopJudgeHelpers() {}

    /**
     * True when a max-iters fallback would be handled by an engine's
     * plan-mode-yield branch (multi-turn continuation) rather than by
     * the user-facing fallback. The judge skips this case — plan-mode
     * already has its own recovery via subsequent turns.
     */
    public static boolean isPlanModeYieldCase(
            ThinkProcessDocument process,
            StructuredActionEngine.ActionLoopResult loopResult) {
        if (!"max-iters".equals(loopResult.fallbackReason())) {
            return false;
        }
        if (!loopResult.madeProgress()) {
            return false;
        }
        ProcessMode mode = process.getMode();
        return mode == ProcessMode.EXECUTING
                || mode == ProcessMode.EXPLORING
                || mode == ProcessMode.PLANNING;
    }

    /**
     * Most-recent user-chat-input text from the current turn's inbox.
     * Falls back to {@code process.getGoal()} when the turn was
     * triggered by a non-UCI event (worker reply, scheduler, …) so the
     * judge still has SOMETHING to anchor its decision on. Returns
     * empty string rather than null — the recipe template doesn't
     * need to handle absent vars.
     */
    public static String lastUserGoal(
            List<SteerMessage> inbox, ThinkProcessDocument process) {
        for (int i = inbox.size() - 1; i >= 0; i--) {
            SteerMessage m = inbox.get(i);
            if (m instanceof SteerMessage.UserChatInput uci
                    && uci.content() != null
                    && !uci.content().isBlank()) {
                return uci.content().trim();
            }
        }
        String goal = process.getGoal();
        return goal == null ? "" : goal.trim();
    }

    /**
     * Walk the per-turn message list and pull out the names (and a
     * short arg preview) of every tool the LLM called this turn — the
     * judge uses this list to spot "called the same fetch three times"
     * style loops. Limited to {@link #JUDGE_TOOLS_USED_MAX} entries to
     * keep the prompt cheap; older calls drop off the front.
     */
    public static List<String> extractToolCallNames(List<ChatMessage> messages) {
        List<String> out = new ArrayList<>();
        for (ChatMessage m : messages) {
            if (m instanceof AiMessage am && am.hasToolExecutionRequests()) {
                for (ToolExecutionRequest call : am.toolExecutionRequests()) {
                    String args = call.arguments();
                    if (args != null && args.length() > 80) {
                        args = args.substring(0, 80) + "…";
                    }
                    out.add(call.name() + "(" + (args == null ? "" : args) + ")");
                }
            }
        }
        if (out.size() > JUDGE_TOOLS_USED_MAX) {
            out.subList(0, out.size() - JUDGE_TOOLS_USED_MAX).clear();
        }
        return out;
    }
}
