package de.mhus.vance.api.roles;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Role of a user or team within one project — its membership. Totally ordered,
 * declaration order is the rank. No role means: not a member.
 */
@GenerateTypeScript("roles")
public enum ProjectRole {
    /** Reads documents and sessions of the project. */
    READER,
    /** Works in the project: writes, starts and steers sessions and processes. */
    MEMBER,
    /**
     * Configures the project ({@code _vance/}, members), archives, deletes and
     * renames it, and authors {@code runAs}-carrying documents.
     */
    ADMIN
}
