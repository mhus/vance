package de.mhus.vance.brain.arthur;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vance.arthur.*} — tunables for the Arthur session-chat engine.
 */
@Data
@ConfigurationProperties(prefix = "vance.arthur")
public class ArthurProperties {

    /**
     * Tool-call iterations granted to an Arthur turn before the
     * action-loop judge
     * ({@link de.mhus.vance.brain.thinkengine.action.ActionLoopJudgeService})
     * is consulted for the first time.
     *
     * <p>Despite the history below this is <em>not</em> a hard cap.
     * {@code ActionLoopJudgeHelpers.JUDGE_EXTENSION_ITERS} grants +6 per
     * extension and there is deliberately no ceiling on the number of
     * extensions, so what this value really controls is <em>how often a
     * long turn stops to ask the judge</em> — and every ask costs an extra
     * LLM call. A runaway is bounded by the judge flipping to synthesize,
     * by ESC / {@code /pause}, and by the per-turn wallclock net.
     *
     * <p>History: 6 originally; bumped to 12 on 2026-06-19 because a
     * research-style turn easily consumes 5–7 reads before the model can
     * synthesise, and 6 cut Arthur off mid-research with a "let me look
     * that up" placeholder as the user-facing reply. That bump was silently
     * defeated until 2026-08-06 by a {@code maxIterations: 6} pin in the
     * bundled arthur recipe — measured on a real coding session, 10 of 29
     * turns hit the cap. Raised to 20 once the pin was removed: with no
     * extension ceiling the conservative value buys nothing but judge
     * round-trips, and tool-driven turns (read → edit → verify chains)
     * routinely need more than 12.
     *
     * <p>{@code ArthurEngine} raises this per plan mode (EXPLORING /
     * PLANNING, and higher again for EXECUTING). Those floors stay
     * meaningful only while this value sits below them — they are floors,
     * not additions.
     */
    private int maxToolIterations = 20;

    /**
     * Show the user a "thinking…" placeholder when a turn doesn't
     * stream any text before the first tool call. Off by default —
     * UI concern, not currently implemented in the foot.
     */
    private boolean placeholderWhenSilent = false;
}
