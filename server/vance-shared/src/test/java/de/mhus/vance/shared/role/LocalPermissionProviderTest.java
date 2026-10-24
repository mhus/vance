package de.mhus.vance.shared.role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.shared.permission.SecurityContext;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalPermissionProviderTest {

    private final RoleAssignmentService assignments = mock(RoleAssignmentService.class);
    private final LocalPermissionProvider provider = new LocalPermissionProvider(assignments);

    @Test
    void projectRole_foldsTeamMembership_strongestWins() {
        when(assignments.forScope("acme", RoleScopeType.PROJECT, "proj"))
                .thenReturn(List.of(
                        row(RoleSubjectType.USER, "alice", ProjectRole.READER),
                        row(RoleSubjectType.TEAM, "eng", ProjectRole.MEMBER),
                        row(RoleSubjectType.TEAM, "ops", ProjectRole.ADMIN)));

        assertThat(provider.projectRole(SecurityContext.user("alice", "acme", List.of("eng")), "acme", "proj"))
                .isEqualTo(ProjectRole.MEMBER);
        assertThat(provider.projectRole(SecurityContext.user("bob", "acme", List.of()), "acme", "proj"))
                .isNull();
    }

    @Test
    void projectRole_teamNamedLikeAUser_isNotThatUser() {
        when(assignments.forScope("acme", RoleScopeType.PROJECT, "proj"))
                .thenReturn(List.of(row(RoleSubjectType.TEAM, "alice", ProjectRole.ADMIN)));

        assertThat(provider.projectRole(SecurityContext.user("alice", "acme", List.of()), "acme", "proj"))
                .isNull();
    }

    @Test
    void storesTenantRoles() {
        assertThat(provider.storesTenantRoles()).isTrue();
    }

    private static RoleAssignmentDocument row(RoleSubjectType type, String id, ProjectRole role) {
        return RoleAssignmentDocument.builder()
                .tenantId("acme")
                .scopeType(RoleScopeType.PROJECT)
                .scopeId("proj")
                .subjectType(type)
                .subjectId(id)
                .projectRole(role)
                .build();
    }
}
