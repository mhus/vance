package de.mhus.vance.brain.ford;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vance.ford.*} — tunables for the Ford chat engine.
 */
@Data
@ConfigurationProperties(prefix = "vance.ford")
public class FordProperties {

    /**
     * Fraction of the model's context window at which memory compaction
     * should fire. {@code 0.9} = compact when the replayed history is
     * estimated to use 90% of {@code contextWindowTokens}.
     */
    private double compactionTriggerRatio = 0.9;

    /**
     * Number of trailing chat messages a compaction keeps verbatim.
     * Older messages get rolled into the summary. Default 10 — enough
     * to preserve the "what we just talked about" feel.
     */
    private int compactionKeepRecent = 10;

    /**
     * Hard cap on the source text passed to the summarizer LLM. Older
     * messages are concatenated; if the total exceeds the cap, the
     * oldest are dropped (they're already older than the rest of the
     * summary anyway). Keeps a runaway summarizer call from taking minutes.
     */
    private int compactionMaxSourceChars = 200_000;
}
