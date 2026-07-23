package de.mhus.vance.simpleauth.brain;

import de.mhus.vance.simpleauth.GrantRole;
import de.mhus.vance.simpleauth.GrantScopeType;
import de.mhus.vance.simpleauth.GrantSubjectType;
import org.jspecify.annotations.Nullable;

/** Wire representation of one permission grant. */
public record PermissionGrantDto(
        @Nullable String id,
        String tenantId,
        GrantScopeType scopeType,
        String scopeId,
        GrantSubjectType subjectType,
        String subjectId,
        GrantRole role,
        @Nullable String createdBy,
        @Nullable Long createdAtMs) {
}
