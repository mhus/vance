package de.mhus.vance.shared.llmusage;

/** The three independent windows an {@link LlmBudget} can limit. All in UTC. */
public enum BudgetWindow {

    /** The current UTC day. */
    DAILY,

    /** The current UTC calendar month. */
    MONTHLY,

    /** From {@link LlmBudget#totalSince()} until today — a grant, not a rate. */
    TOTAL;

    /** Lowercase name for messages and the wire. */
    public String label() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
