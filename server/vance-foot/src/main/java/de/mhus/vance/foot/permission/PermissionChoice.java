package de.mhus.vance.foot.permission;

/**
 * The four answers to an interactive permission prompt. {@code *_ONCE}
 * applies to the current tool call only; {@code *_ALWAYS} additionally
 * persists an exact-match rule to the central {@code ~/.vance/permissions.yaml}
 * so the same subject is auto-resolved next time.
 */
public enum PermissionChoice {
    ALLOW_ONCE,
    ALLOW_ALWAYS,
    DENY_ONCE,
    DENY_ALWAYS;

    public boolean isAllow() {
        return this == ALLOW_ONCE || this == ALLOW_ALWAYS;
    }

    public boolean isAlways() {
        return this == ALLOW_ALWAYS || this == DENY_ALWAYS;
    }

    /** Maps the 1-based menu number to a choice, or {@code null} if out of range. */
    public static @org.jspecify.annotations.Nullable PermissionChoice fromMenuNumber(int n) {
        return switch (n) {
            case 1 -> ALLOW_ONCE;
            case 2 -> ALLOW_ALWAYS;
            case 3 -> DENY_ONCE;
            case 4 -> DENY_ALWAYS;
            default -> null;
        };
    }
}
