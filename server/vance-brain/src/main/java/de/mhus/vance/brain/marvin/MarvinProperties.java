package de.mhus.vance.brain.marvin;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Brain-wide tunables for the Marvin engine. All values can be
 * overridden per-process via {@code engineParams}; these are only
 * the brain-level fallbacks.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "vance.marvin")
public class MarvinProperties {

    /** Hard cap on total nodes per Marvin tree — guards against
     *  runaway NEEDS_SUBTASKS recursion. */
    private int maxTreeNodes = 200;

    /** Default max tree depth. Recipes can override via
     *  {@code params.maxTreeDepth}. */
    private int maxTreeDepth = 5;

    /** Default REFLECT iteration cap. */
    private int reflectMaxIterations = 3;

    /** Default VALIDATE iteration cap. */
    private int validateMaxIterations = 2;

    /** Default CONCLUDE retry cap (RETRY_CONCLUDE loops). */
    private int concludeMaxRetries = 2;

    /** Default cap on the VALIDATE(NEED_MORE_DATA) → REFLECT loop
     *  (code-review B3 — prevents an unbounded synchronous phase cycle,
     *  e.g. with the empty-availableRecipes default). */
    private int needMoreDataMaxIterations = 4;

    /** Hard cap on chars of a CALL_RECIPE reply pasted into the
     *  worker's memory. Longer replies get truncated with a marker;
     *  the full reply stays in the sub-process's persistent chat
     *  history. */
    private int recipeReplyTruncateChars = 8000;
}
