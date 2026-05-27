package de.mhus.vance.shared.prak;

import org.jspecify.annotations.Nullable;

/**
 * Audit metadata about the message range a single analyzer pass saw.
 *
 * <p>{@code fromTurnId} / {@code toTurnId} are inclusive ends in
 * chronological order. {@code messagesAnalyzed} is the total count
 * including system / tool messages — useful for calibration analytics.
 *
 * <p>For triggers that don't operate on raw chat turns (AutoDream-
 * aggregation over {@code ARCHIVED_CHAT}s, background-consistency
 * over memory clusters), {@code fromTurnId} / {@code toTurnId} may be
 * {@code null} and {@code messagesAnalyzed} stores the item count
 * instead.
 */
public record WindowSpan(
        @Nullable String fromTurnId,
        @Nullable String toTurnId,
        int messagesAnalyzed) {
}
