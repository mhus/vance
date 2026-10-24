/**
 * Authorization layer — answers "is this subject allowed to perform this
 * action on that resource?".
 *
 * <p>Distinct from {@link de.mhus.vance.shared.access}, which only handles
 * <em>authentication</em> (JWT verification). Once a request is authenticated,
 * inbound layers translate the JWT claims into a {@link SecurityContext} and
 * call {@link PermissionService#enforce} before delegating to a service.
 *
 * <p>The role model is core: the roles ({@code TenantRole}, {@code ProjectRole}
 * in {@code vance-api}), what each one permits ({@link RoleTable}) and the
 * structural rules ({@link CorePreconditions}). A {@link PermissionProvider}
 * only reports who holds which role and may veto; the core's local provider
 * ({@code de.mhus.vance.shared.role}) does so from {@code role_assignments}
 * unless an addon contributes another one.
 *
 * <p>Convention:
 * <ul>
 *   <li>Inbound layers (REST controllers, WS handlers, slash-command dispatchers)
 *       call {@link PermissionService#enforce} explicitly. No annotations, no AOP.</li>
 *   <li>Call sites speak {@code (Resource, Action)} — never role names. The
 *       few places that are about roles themselves use the role-shaped methods
 *       of {@link PermissionService}.</li>
 *   <li>Services trust their callers; they do not re-check.</li>
 *   <li>Background jobs and lifecycle listeners use
 *       {@link SecurityContext#SYSTEM} so the check is recorded but allowed.</li>
 * </ul>
 */
@NullMarked
package de.mhus.vance.shared.permission;

import org.jspecify.annotations.NullMarked;
