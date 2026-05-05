package de.mhus.vance.api.vogon;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One switch-case in a {@link ScorerSpec}. Pairs a {@link ScoreMatch}
 * with a list of {@link BranchAction}s. The first case whose match
 * fires consumes the score; later cases are skipped.
 *
 * <p>See {@code specification/vogon-engine.md} §2.5.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScorerCase {

    /** Required — when this matches, this case wins. */
    private ScoreMatch when = new ScoreMatch();

    /** Action list, executed in declared order. A terminal action
     *  (see {@link BranchAction#terminal()}) aborts the rest. */
    @Builder.Default
    private List<BranchAction> doActions = new ArrayList<>();
}
