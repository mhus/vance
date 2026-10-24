/**
 * Wire contract of the role model: the tenant and project roles, and the DTOs
 * of the role-administration REST surface.
 *
 * <p>Served by {@code de.mhus.vance.brain.roles.RoleAdminController} under
 * {@code /brain/{tenant}/admin/roles}; the caller's own roles travel in
 * {@code MeDto} ({@code GET /brain/{tenant}/me}). What each role permits is decided in
 * {@code vance-shared} ({@code PermissionService}); this package only names
 * them. See {@code specification/public/permission-system.md}.
 */
@NullMarked
package de.mhus.vance.api.roles;

import org.jspecify.annotations.NullMarked;
