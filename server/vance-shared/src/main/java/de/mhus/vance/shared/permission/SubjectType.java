package de.mhus.vance.shared.permission;

/**
 * What kind of principal owns a {@link SecurityContext}.
 *
 * <p>Teams are not a {@code SubjectType} — they are a way to <em>aggregate</em>
 * permissions for users. The user's team memberships travel inside
 * {@link SecurityContext#teams()} so the resolver can fold them in.
 */
public enum SubjectType {
    /** A regular authenticated user — JWT-backed. */
    USER,
    /** Internal callers (schedulers, lifecycle listeners, migrations). */
    SYSTEM
}
