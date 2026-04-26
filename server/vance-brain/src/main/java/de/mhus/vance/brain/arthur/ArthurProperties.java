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
     */
    private int maxToolIterations = 6;

    /**
     * Show the user a "thinking…" placeholder when a turn doesn't
     * stream any text before the first tool call. Off by default —
     * UI concern, not currently implemented in the foot.
     */
    private boolean placeholderWhenSilent = false;
}
