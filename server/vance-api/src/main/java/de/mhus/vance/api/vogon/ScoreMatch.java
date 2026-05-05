package de.mhus.vance.api.vogon;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Match operator for a {@link ScorerCase}. Exactly one of
 * {@link #scoreAtLeast}, {@link #scoreBelow}, {@link #scoreBetween}
 * or {@link #defaultMatch} is set. Mutually exclusive — strategy-load
 * validation enforces it.
 *
 * <p>{@link #defaultMatch} is the catch-all and must appear as the
 * last case in a {@link ScorerSpec#cases()} list when used.
 *
 * <p>See {@code specification/vogon-engine.md} §2.5.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScoreMatch {

    /** Match if {@code score >= scoreAtLeast}. */
    private @Nullable Double scoreAtLeast;

    /** Match if {@code score < scoreBelow}. */
    private @Nullable Double scoreBelow;

    /** Match if {@code lo <= score < hi}. Two-element array
     *  {@code [lo, hi]} when set. */
    private @Nullable double[] scoreBetween;

    /** Catch-all marker. {@code true} = match anything not yet matched
     *  by an earlier case. */
    @Builder.Default
    private boolean defaultMatch = false;
}
