/**
 * Wire contract of the role model: the tenant and project roles, and the DTOs
 * of the role-administration REST surface.
 *
 * <p>Served by {@code de.mhus.vance.brain.roles.RoleAdminController} under
 * {@code /brain/{tenant}/admin/roles} and by
 * {@code de.mhus.vance.brain.roles.MyRolesController} under
 * {@code /brain/{tenant}/me/roles}. What each role permits is decided in
 * {@code vance-shared} ({@code PermissionService}); this package only names
 * them. See {@code specification/public/permission-system.md}.
 */
@NullMarked
package de.mhus.vance.api.roles;

import org.jspecify.annotations.NullMarked;
