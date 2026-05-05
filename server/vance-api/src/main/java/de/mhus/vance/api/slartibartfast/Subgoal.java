package de.mhus.vance.api.slartibartfast;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One step in the decomposition of a {@link FramedGoal}. The
 * BINDING validator enforces the central rigor rule: every subgoal
 * is either evidence-based ({@link #getEvidenceRefs()} non-empty,
 * not exclusively pointing at {@link ClassificationKind#OPINION} /
 * {@link ClassificationKind#OUTDATED} claims) or explicitly
 * speculative ({@link #isSpeculative()} {@code true} with a
 * non-blank {@link #getSpeculationRationale()}). No third option.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Subgoal {

    /** Stable id. Conventional shape: {@code "sg1"}, {@code "sg2"}, … */
    private String id = "";

    /** What this step achieves, in declarative form. */
    private String goal = "";

    /** Foreign keys into {@link Claim#getId()}. Empty iff
     *  {@link #isSpeculative()} is {@code true}. Validator rejects
     *  dangling refs. */
    @Builder.Default
    private List<String> evidenceRefs = new ArrayList<>();

    /** Foreign keys into {@link Criterion#getId()} — which
     *  acceptance criteria this subgoal helps satisfy. Used by
     *  the VALIDATING phase to ensure full criterion coverage. */
    @Builder.Default
    private List<String> criterionRefs = new ArrayList<>();

    /** When {@code true}, this subgoal is acknowledged as a guess
     *  rather than a derived plan element. {@link #getSpeculationRationale()}
     *  must explain why no firm evidence is available. */
    private boolean speculative;

    /** Required when {@link #isSpeculative()} is {@code true} —
     *  human-readable explanation of why the planner couldn't find
     *  evidence and what assumption is being made. */
    private @Nullable String speculationRationale;
}
