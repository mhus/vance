package de.mhus.vance.shared.permission;

/**
 * Strategy for evaluating permission checks. Exactly one bean must be active —
 * {@link PermissionService} enforces that at startup. This module ships no
 * implementation; a provider addon contributes one (allow-all for dev/test,
 * simple-auth for production, or an enterprise governor). Swapping the addon
 * swaps the behaviour without touching any call site.
 *
 * <p>Implementations must be stateless and side-effect-free apart from logging
 * — they are called from request threads and must not block on remote I/O for
 * data that should be pre-resolved into the {@link SecurityContext}.
 */
public interface PermissionResolver {

    /**
     * @return {@code true} iff {@code subject} may perform {@code action} on
     *         {@code resource}. Implementations should never throw — return
     *         {@code false} on missing data.
     */
    boolean isAllowed(SecurityContext subject, Resource resource, Action action);

    /**
     * Reason-aware variant — lets a resolver treat a trusted internal write
     * ({@link WriteReason#SYSTEM}) differently from a user write while the
     * {@code subject} still carries the real actor. The default ignores the
     * reason (reason-unaware providers, e.g. allow-all, behave unchanged);
     * the simple-auth resolver overrides it.
     */
    default boolean isAllowed(
            SecurityContext subject, Resource resource, Action action, WriteReason reason) {
        return isAllowed(subject, resource, action);
    }
}
