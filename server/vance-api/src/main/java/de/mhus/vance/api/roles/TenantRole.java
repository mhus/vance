package de.mhus.vance.api.roles;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Role of a user within a tenant. Totally ordered — declaration order is the
 * rank, every role includes everything of the roles before it.
 *
 * <p>Every authenticated member of a tenant holds at least {@link #USER}; only
 * the higher roles are stored. Tenant roles are given to users only, never to
 * teams.
 */
@GenerateTypeScript("roles")
public enum TenantRole {
    /** Works in the projects they are a member of. */
    USER,
    /** Additionally creates projects (and becomes their project admin). */
    CREATOR,
    /**
     * Additionally manages users and teams and has full rights in every
     * project of the tenant — except writing the tenant configuration
     * ({@code _tenant}).
     */
    ADMIN,
    /** Additionally writes the tenant configuration and appoints tenant admins. */
    TENANT_ADMIN
}
