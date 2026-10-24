package de.mhus.vance.api.roles;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/** Where a role assignment applies: the whole tenant, or one project. */
@GenerateTypeScript("roles")
public enum RoleScopeType {
    TENANT,
    PROJECT
}
