package de.mhus.vance.api.vogon;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One match-case in a {@link DeciderSpec}. {@link #when} is the option
 * token (case-insensitive equality against the worker reply). First
 * matching case wins.
 *
 * <p>See {@code specification/vogon-engine.md} §2.6.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeciderCase {

    /** Token to match the classifier reply against. */
    private String when = "";

    /** Action list, same vocabulary as {@link ScorerCase}. */
    @Builder.Default
    private List<BranchAction> doActions = new ArrayList<>();
}
