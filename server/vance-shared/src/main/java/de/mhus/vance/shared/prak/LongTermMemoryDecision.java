package de.mhus.vance.shared.prak;

import org.jspecify.annotations.Nullable;

/**
 * The analyzer's verdict on what to do with this specific item.
 *
 * <p>{@link #rationale()} is informational; the {@link
 * de.mhus.vance.shared.prak.LongTermMemoryAction action}
 * is the load-bearing field consumed by the promotion service.
 */
public record LongTermMemoryDecision(
        LongTermMemoryAction action,
        @Nullable String rationale) {

    public static LongTermMemoryDecision skip(String rationale) {
        return new LongTermMemoryDecision(LongTermMemoryAction.SKIP, rationale);
    }

    public static LongTermMemoryDecision promote(String rationale) {
        return new LongTermMemoryDecision(LongTermMemoryAction.PROMOTE, rationale);
    }

    public static LongTermMemoryDecision inboxOffer(String rationale) {
        return new LongTermMemoryDecision(LongTermMemoryAction.INBOX_OFFER, rationale);
    }

    public static LongTermMemoryDecision refresh(String rationale) {
        return new LongTermMemoryDecision(LongTermMemoryAction.REFRESH, rationale);
    }
}
