package de.mhus.vance.brain.memory.evaluation;

import de.mhus.vance.shared.memory.evaluation.ItemCountExpectation;
import org.jspecify.annotations.Nullable;

/**
 * Coarse profile of a candidate span, computed by the {@link CheapPathFilter}.
 *
 * <p>The numbers below feed two decisions:
 * <ul>
 *   <li>{@link #isSkippable()} — should the analyzer be called at all?</li>
 *   <li>{@link #expectation()} — how many items do we expect, so the
 *       sanitizer's hard cap is meaningful?</li>
 * </ul>
 *
 * <p>If {@link #isSkippable()} returns {@code true}, {@link #skipReason()}
 * carries a short human label for telemetry.
 */
public record SpanProfile(
        int messageCount,
        int approxTokenCount,
        int substantialUserTurnCount,
        int markerHits,
        int trivialAckCount,
        int selfNarrationCount,
        @Nullable String skipReason) {

    /** Cheap-path skip threshold (§4a.2: "Span < ~50 Tokens → skip"). */
    static final int MIN_TOKEN_COUNT = 50;

    public boolean isSkippable() {
        return skipReason != null;
    }

    public ItemCountExpectation expectation() {
        if (markerHits > 0) {
            return ItemCountExpectation.MARKER_RICH;
        }
        if (substantialUserTurnCount == 0) {
            return ItemCountExpectation.ACK_ONLY;
        }
        return ItemCountExpectation.NORMAL;
    }
}
