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

    /** Hard cap on PLAN children per single LLM call. */
    private int planMaxChildren = 8;

    /** Hard cap on AGGREGATE summary length (chars). */
    private int aggregateMaxOutputChars = 4000;

    /** Hard cap on total nodes per Marvin tree — guards against
     *  runaway PLAN recursion. */
    private int maxTreeNodes = 200;
}
