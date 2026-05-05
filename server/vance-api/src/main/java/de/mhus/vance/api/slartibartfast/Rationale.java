package de.mhus.vance.api.slartibartfast;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * First-class reason record. Every non-trivial decision in the
 * planner — why this criterion exists, why this manual was
 * consulted, why this claim got classified FACT, why the
 * decomposition has this shape — references a {@link Rationale}
 * by id from {@link ArchitectState#getRationales()}.
 *
 * <p>Rationales are <em>append-only</em>; rationales referenced
 * by an artifact must remain in the pool even when the artifact is
 * later replaced (e.g. when DECOMPOSING re-runs after a BINDING
 * failure, the new subgoals have new rationaleIds — the old
 * rationales stay for audit).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Rationale {

    /** Stable id within the run. Conventional shape:
     *  {@code "rt1"}, {@code "rt2"}, … */
    private String id = "";

    /** Human-readable explanation. Short enough that it fits in
     *  a re-prompt hint or an audit dump line. */
    private String text = "";

    /** Optional foreign keys into other artifacts —
     *  {@link Claim#getId()}, {@link EvidenceSource#getId()},
     *  {@link Criterion#getId()}, {@link Subgoal#getId()}. The
     *  validator checks these resolve. */
    @Builder.Default
    private List<String> sourceRefs = new ArrayList<>();

    /** Phase that produced this rationale — useful when an
     *  audit dump wants to group reasons by lifecycle stage. */
    @Builder.Default
    private ArchitectStatus inferredAt = ArchitectStatus.READY;
}
