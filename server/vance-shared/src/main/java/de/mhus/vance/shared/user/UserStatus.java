package de.mhus.vance.shared.user;

/**
 * Lifecycle state of a {@link UserDocument}.
 *
 * <p>Replaces the earlier {@code enabled} boolean — richer state is needed
 * as soon as the login flow exists (e.g. email-verification step, admin
 * disable).
 */
public enum UserStatus {

    /** Normal, fully usable account. Default for newly created users. */
    ACTIVE,

    /** Admin-disabled or self-deactivated — may re-activate later. */
    DISABLED,

    /** Created but awaiting something (e.g. email verification). Cannot log in. */
    PENDING
}
