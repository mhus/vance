package de.mhus.vance.shared.permission;

/**
 * Strategy for evaluating permission checks. Exactly one bean is active at a
 * time. The default in this module is {@link AllowAllPermissionResolver};
 * downstream applications swap it by registering their own
 * {@code PermissionResolver} bean.
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
}
