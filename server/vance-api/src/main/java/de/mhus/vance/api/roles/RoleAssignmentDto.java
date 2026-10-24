package de.mhus.vance.api.roles;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One stored role assignment. Exactly one of {@link #tenantRole} (scope
 * {@code TENANT}) and {@link #projectRole} (scope {@code PROJECT}) is set.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("roles")
public class RoleAssignmentDto {

    private RoleScopeType scopeType;

    /** Project name for {@code PROJECT}; the tenant name for {@code TENANT}. */
    private String scopeId;

    private RoleSubjectType subjectType;

    /** Username or team name. */
    private String subjectId;

    private @Nullable TenantRole tenantRole;

    private @Nullable ProjectRole projectRole;

    /** Who made the assignment (audit only). */
    private @Nullable String createdBy;

    private @Nullable Instant createdAt;
}
