package de.mhus.vance.shared.llmusage;

/**
 * Whether the attempt a ledger row describes produced an answer.
 *
 * <p>Recorded because the accounting layer sits <i>inside</i> the retry
 * decorator and therefore sees every attempt: a provider that consumed a
 * full prompt and then failed has cost money. Before, only the winning
 * attempt was booked and a retry storm was invisible.
 *
 * <p>The report must keep the two apart. Summing failures into the cost
 * figure would make the number worse after this change, not better —
 * they are shown as their own count next to the amount.
 */
public enum UsageOutcome {

    /** Provider answered. */
    SUCCESS,

    /** Attempt raised — tokens may still have been billed by the vendor. */
    FAILED
}
