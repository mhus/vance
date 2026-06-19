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
     * Hard cap on tool-call iterations per Arthur turn. Keeps a
     * looping LLM from racking up cost when the model misreads a
     * tool result and re-invokes the same chain forever.
     *
     * <p>Bumped from 6 to 12 (2026-06-19): a single research-style
     * turn easily consumes 5–7 reads (search + fetch + transcript +
     * a couple of result reads + a re-fetch) before the model can
     * synthesise; 6 cut Arthur off mid-research with the LLM's
     * "let me look that up" placeholder as the user-facing reply.
     * 12 covers the typical research-and-answer pattern; the
     * action-loop judge ({@link de.mhus.vance.brain.thinkengine.action.ActionLoopJudgeService})
     * then decides whether to extend further or synthesise from
     * what's gathered so far.
     */
    private int maxToolIterations = 12;

    /**
     * Show the user a "thinking…" placeholder when a turn doesn't
     * stream any text before the first tool call. Off by default —
     * UI concern, not currently implemented in the foot.
     */
    private boolean placeholderWhenSilent = false;
}
