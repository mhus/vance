package de.mhus.vance.simpleauth.brain;

import de.mhus.vance.simpleauth.GrantRole;
import de.mhus.vance.simpleauth.GrantScopeType;
import de.mhus.vance.simpleauth.GrantSubjectType;

/** Request body for setting a grant. Tenant comes from the path. */
public record GrantCreateRequest(
        GrantScopeType scopeType,
        String scopeId,
        GrantSubjectType subjectType,
        String subjectId,
        GrantRole role) {
}
