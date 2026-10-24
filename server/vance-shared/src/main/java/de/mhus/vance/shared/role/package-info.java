/**
 * Role storage inside Vance and the local permission provider.
 *
 * <p>{@code role_assignments} holds who has which role: tenant roles (users
 * only) and project memberships (users and teams). {@link
 * de.mhus.vance.shared.role.LocalPermissionProvider} reads it and is the
 * instance's role source unless an addon contributes another
 * {@link de.mhus.vance.shared.permission.PermissionProvider} — project
 * memberships are read from here either way.
 *
 * <p>Changing roles goes through {@link
 * de.mhus.vance.shared.role.RoleAdministrationService}, which carries the
 * assignment rules (nobody assigns above their own role, the last tenant admin
 * stays, tenant roles only for persons). {@link
 * de.mhus.vance.shared.role.RoleBootstrap} seeds initial roles from trusted
 * code. See {@code specification/public/permission-system.md}.
 */
@NullMarked
package de.mhus.vance.shared.role;

import org.jspecify.annotations.NullMarked;
