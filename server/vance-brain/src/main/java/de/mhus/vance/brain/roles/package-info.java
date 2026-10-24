/**
 * Brain surfaces of the role model: the role-administration REST endpoints,
 * {@code me/roles} for the Web-UI, the LLM tools that list roles and
 * <em>request</em> role changes, and the inbox effect that carries out an
 * approved request. The rules themselves live in {@code vance-shared}
 * ({@code PermissionService}, {@code RoleAdministrationService}). See
 * {@code specification/public/permission-system.md}.
 */
@NullMarked
package de.mhus.vance.brain.roles;

import org.jspecify.annotations.NullMarked;
