package de.mhus.vance.shared.role;

import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.api.roles.TenantRole;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One role assignment: subject {@code (subjectType, subjectId)} holds a role on
 * scope {@code (scopeType, scopeId)} within {@code tenantId}. Exactly one
 * assignment per subject and scope (unique key) — assigning again replaces the
 * role. Additive only: there are no deny assignments.
 *
 * <p>Scope {@code TENANT} carries {@link #tenantRole} and only ever a USER
 * subject; scope {@code PROJECT} carries {@link #projectRole}. The implicit
 * tenant role {@link TenantRole#USER} is never stored.
 */
@Document(collection = "role_assignments")
@CompoundIndexes({
    @CompoundIndex(
            name = "role_key_idx",
            def = "{ 'tenantId':1, 'scopeType':1, 'scopeId':1, 'subjectType':1, 'subjectId':1 }",
            unique = true),
    @CompoundIndex(name = "role_scope_idx", def = "{ 'tenantId':1, 'scopeType':1, 'scopeId':1 }"),
    @CompoundIndex(name = "role_subject_idx", def = "{ 'tenantId':1, 'subjectType':1, 'subjectId':1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleAssignmentDocument {

    @Id
    private @Nullable String id;

    /** {@code TenantDocument.name}. */
    @Builder.Default
    private String tenantId = "";

    @Builder.Default
    private RoleScopeType scopeType = RoleScopeType.PROJECT;

    /** PROJECT: {@code ProjectDocument.name}. TENANT: equals {@code tenantId} (self-reference). */
    @Builder.Default
    private String scopeId = "";

    @Builder.Default
    private RoleSubjectType subjectType = RoleSubjectType.USER;

    /** {@code UserDocument.name} or {@code TeamDocument.name}. */
    @Builder.Default
    private String subjectId = "";

    /** Set iff {@link #scopeType} is {@code TENANT}. */
    private @Nullable TenantRole tenantRole;

    /** Set iff {@link #scopeType} is {@code PROJECT}. */
    private @Nullable ProjectRole projectRole;

    /** Username of whoever made the assignment, or {@code bootstrap} (audit only). */
    private @Nullable String createdBy;

    @CreatedDate
    private @Nullable Instant createdAt;
}
