package de.mhus.vance.shared.llmusage;

/**
 * The levels an {@link LlmBudget} can be set on. Each counts its own
 * consumption, and a call has to pass every level that applies to it.
 */
public enum BudgetScope {

    /** The whole tenant — settings of the {@code _tenant} project. */
    TENANT,

    /** One project — settings of that project. */
    PROJECT;

    /** Lowercase name for messages and the wire. */
    public String label() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
