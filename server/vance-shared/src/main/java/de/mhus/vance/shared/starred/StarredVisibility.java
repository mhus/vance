package de.mhus.vance.shared.starred;

/**
 * How far up the visibility ladder a starred entry sits. The on-disk form keeps
 * two independent, human-readable switches ({@code enabled}, {@code hidden});
 * this enum is the single three-valued state derived from them.
 *
 * <pre>
 * DISABLED  →  HIDDEN  →  VISIBLE
 * </pre>
 *
 * <p>Same shape as {@code SettingType}'s protection ladder
 * ({@code string → hidden → password}): the two predicates below are thresholds
 * on it and <b>both monotone</b>, expressed as {@code ordinal() >= …} so a stage
 * inserted later behaves correctly without touching either one.
 *
 * <p><b>Hard rule, same as SettingType:</b> no {@code == StarredVisibility.HIDDEN}
 * anywhere in the tree except inside this enum — and not in TypeScript either.
 * A comparison instead of the predicate silently excludes a state, and "silently
 * excluded" here means a "send to" that cannot find its target app even though
 * the user registered it.
 */
public enum StarredVisibility {

    /** Switched off entirely — not displayed, and not handed out on request. */
    DISABLED,

    /** Registered but out of the way: the service resolves it, the landing page does not show it. */
    HIDDEN,

    /** Registered and shown. */
    VISIBLE;

    /** Whether the service hands this entry out when explicitly asked. */
    public boolean resolvable() {
        return ordinal() >= HIDDEN.ordinal();
    }

    /** Whether the landing page shows this entry. */
    public boolean displayed() {
        return ordinal() >= VISIBLE.ordinal();
    }

    /**
     * Derive the state from the two on-disk switches. {@code enabled} wins:
     * a disabled entry is gone regardless of {@code hidden}, so the two flags
     * can never contradict each other.
     */
    public static StarredVisibility of(boolean enabled, boolean hidden) {
        if (!enabled) return DISABLED;
        return hidden ? HIDDEN : VISIBLE;
    }
}
