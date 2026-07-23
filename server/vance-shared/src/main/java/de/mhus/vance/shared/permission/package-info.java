/**
 * Authorization layer — answers "is this subject allowed to perform this
 * action on that resource?".
 *
 * <p>Distinct from {@link de.mhus.vance.shared.access}, which only handles
 * <em>authentication</em> (JWT verification). Once a request is authenticated,
 * inbound layers translate the JWT claims into a {@link SecurityContext} and
 * call {@link PermissionService#enforce} before delegating to a service.
 *
 * <p>The actual rule evaluation is delegated to a {@link PermissionResolver}.
 * This module ships only the SPI — the concrete resolver is contributed by a
 * provider addon (allow-all for dev/test, simple-auth for production, or an
 * enterprise governor). {@link PermissionService} requires exactly one such
 * provider on the classpath and fails startup fast otherwise. Swapping the
 * provider addon changes the whole authorization behaviour without touching a
 * single call site.
 *
 * <p>Convention:
 * <ul>
 *   <li>Inbound layers (REST controllers, WS handlers, slash-command dispatchers)
 *       call {@link PermissionService#enforce} explicitly. No annotations, no AOP.</li>
 *   <li>Services trust their callers; they do not re-check.</li>
 *   <li>Background jobs and lifecycle listeners use
 *       {@link SecurityContext#SYSTEM} so the check is recorded but allowed.</li>
 * </ul>
 */
@NullMarked
package de.mhus.vance.shared.permission;

import org.jspecify.annotations.NullMarked;
