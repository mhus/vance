package de.mhus.vance.api.roles;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Who holds a role assignment. A {@link #TEAM} holds project roles only — every
 * member of the team gets the role in that project.
 */
@GenerateTypeScript("roles")
public enum RoleSubjectType {
    USER,
    TEAM
}
