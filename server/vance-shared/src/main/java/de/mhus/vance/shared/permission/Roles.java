package de.mhus.vance.shared.permission;

import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.TenantRole;
import org.jspecify.annotations.Nullable;

/**
 * Rank comparisons for the totally ordered role enums. The enums live in
 * {@code vance-api} as plain wire types; their order is the declaration order
 * and is interpreted here, not there.
 */
public final class Roles {

    private Roles() {}

    /** {@code actual} is at least as strong as {@code required}. */
    public static boolean atLeast(TenantRole actual, TenantRole required) {
        return actual.ordinal() >= required.ordinal();
    }

    /** {@code actual} is at least as strong as {@code required}; {@code null} (no member) never is. */
    public static boolean atLeast(@Nullable ProjectRole actual, ProjectRole required) {
        return actual != null && actual.ordinal() >= required.ordinal();
    }

    /** The stronger of two project roles; {@code null} counts as weakest. */
    public static @Nullable ProjectRole max(@Nullable ProjectRole a, @Nullable ProjectRole b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
