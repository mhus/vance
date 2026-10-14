package de.mhus.vance.brain.wonko;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A non-trivial decision of the run with its reason (plan §4a.8): an escape
 * outcome of the model, a discarded finding, a dropped item. The report can
 * say for every such point why it stands as it does.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WonkoDecision {

    /** What was decided about — item, claim or finding, readable. */
    private String subject = "";

    /** e.g. {@code discarded}, {@code cannot_determine}, {@code none}, {@code not_decomposable}. */
    private String outcome = "";

    private String reason = "";
}
