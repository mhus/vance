package de.mhus.vance.brain.roles;

import de.mhus.vance.api.roles.RoleAssignmentDto;
import de.mhus.vance.shared.role.RoleAssignmentDocument;

/** {@link RoleAssignmentDocument} → wire DTO. */
final class RoleMapper {

    private RoleMapper() {}

    static RoleAssignmentDto toDto(RoleAssignmentDocument doc) {
        return RoleAssignmentDto.builder()
                .scopeType(doc.getScopeType())
                .scopeId(doc.getScopeId())
                .subjectType(doc.getSubjectType())
                .subjectId(doc.getSubjectId())
                .tenantRole(doc.getTenantRole())
                .projectRole(doc.getProjectRole())
                .createdBy(doc.getCreatedBy())
                .createdAt(doc.getCreatedAt())
                .build();
    }
}
