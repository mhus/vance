package de.mhus.vance.simpleauth;

/**
 * Total-ordered role. {@code READER < WRITER < ADMIN} — declaration order is
 * the rank, so {@link #atLeast} is an ordinal comparison. This is the model of
 * <em>this</em> provider; the abstract enforcement layer never sees it.
 */
public enum GrantRole {
    READER,
    WRITER,
    ADMIN;

    /** True iff this role is at least as strong as {@code required}. */
    public boolean atLeast(GrantRole required) {
        return ordinal() >= required.ordinal();
    }

    /** The stronger of two roles. */
    public static GrantRole max(GrantRole a, GrantRole b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
