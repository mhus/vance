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
 * The default bean is {@link AllowAllPermissionResolver}, which permits
 * everything and only logs the check on DEBUG. Real implementations replace
 * this bean later (e.g. role-based memberships) without touching call sites.
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
