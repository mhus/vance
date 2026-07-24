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
 *
 * <p><b>A provider only ever evaluates genuine user policy.</b> The framework
 * trust boundary — a {@link SubjectType#SYSTEM} subject (the server acting as
 * itself) or a {@link WriteReason#SYSTEM} write (server code vouching for a
 * write) — is short-circuited by {@link PermissionService} <em>before</em>
 * delegation, so no provider sees it and none can accidentally break internal
 * server operations. {@link WriteReason} is therefore not part of this SPI.
 */
public interface PermissionResolver {

    /**
     * @return {@code true} iff {@code subject} may perform {@code action} on
     *         {@code resource}. Implementations should never throw — return
     *         {@code false} on missing data.
     */
    boolean isAllowed(SecurityContext subject, Resource resource, Action action);
}
