package de.mhus.vance.shared.role;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.api.roles.TenantRole;
import de.mhus.vance.shared.role.request.RoleRequestService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoleBootstrapTest {

    private static final String AGENT = "_trillian-colin-5127";

    @Mock
    RoleAssignmentService assignments;

    @Mock
    RoleRequestService requests;

    @InjectMocks
    RoleBootstrap bootstrap;

    @Test
    void grantTenantAdmin_storesTenantAdmin() {
        bootstrap.grantTenantAdmin("acme", "root");

        verify(assignments).setTenantRole("acme", "root", TenantRole.TENANT_ADMIN, RoleBootstrap.CREATED_BY);
    }

    @Test
    void grantProjectAdmin_storesProjectAdmin() {
        bootstrap.grantProjectAdmin("acme", "proj", "alice");

        verify(assignments)
                .setProjectRole(
                        "acme", "proj", RoleSubjectType.USER, "alice", ProjectRole.ADMIN, RoleBootstrap.CREATED_BY);
    }

    @Test
    void grantProjectMember_seedsMember() {
        when(assignments.find("acme", RoleScopeType.PROJECT, "proj", RoleSubjectType.USER, AGENT))
                .thenReturn(Optional.empty());

        bootstrap.grantProjectMember("acme", "proj", AGENT);

        verify(assignments)
                .setProjectRole(
                        "acme", "proj", RoleSubjectType.USER, AGENT, ProjectRole.MEMBER, RoleBootstrap.CREATED_BY);
    }

    @Test
    void grantProjectMember_lowersWhatAnEarlierSeedSet() {
        when(assignments.find("acme", RoleScopeType.PROJECT, "proj", RoleSubjectType.USER, AGENT))
                .thenReturn(Optional.of(row(ProjectRole.ADMIN, RoleBootstrap.CREATED_BY)));

        bootstrap.grantProjectMember("acme", "proj", AGENT);

        verify(assignments)
                .setProjectRole(
                        "acme", "proj", RoleSubjectType.USER, AGENT, ProjectRole.MEMBER, RoleBootstrap.CREATED_BY);
    }

    @Test
    void grantProjectMember_keepsADeliberateRole() {
        when(assignments.find("acme", RoleScopeType.PROJECT, "proj", RoleSubjectType.USER, AGENT))
                .thenReturn(Optional.of(row(ProjectRole.READER, "marvin.acme")));

        bootstrap.grantProjectMember("acme", "proj", AGENT);

        verify(assignments, never()).setProjectRole(any(), any(), any(), any(), any(), any());
    }

    @Test
    void grantProjectTeamMember_storesTeamMembership() {
        bootstrap.grantProjectTeamMember("acme", "proj", "eng");

        verify(assignments)
                .setProjectRole(
                        "acme", "proj", RoleSubjectType.TEAM, "eng", ProjectRole.MEMBER, RoleBootstrap.CREATED_BY);
    }

    @Test
    void revokeAll_removesEveryRoleAndExpiresRequests() {
        when(assignments.removeAll("acme", RoleSubjectType.USER, AGENT)).thenReturn(3);

        bootstrap.revokeAll("acme", AGENT);

        verify(assignments).removeAll("acme", RoleSubjectType.USER, AGENT);
        // A request naming a deleted account could otherwise be approved onto a
        // later account that reuses the name.
        verify(requests).expireForSubject("acme", RoleSubjectType.USER, AGENT);
    }

    @Test
    void revokeAll_unknownUser_isNoOp() {
        assertThatCode(() -> bootstrap.revokeAll("acme", "ghost")).doesNotThrowAnyException();
    }

    @Test
    void revokeAllOfTeam_removesTeamRolesAndExpiresRequests() {
        bootstrap.revokeAllOfTeam("acme", "rd");

        verify(assignments).removeAll("acme", RoleSubjectType.TEAM, "rd");
        verify(requests).expireForSubject("acme", RoleSubjectType.TEAM, "rd");
    }

    private static RoleAssignmentDocument row(ProjectRole role, String createdBy) {
        return RoleAssignmentDocument.builder()
                .tenantId("acme")
                .scopeType(RoleScopeType.PROJECT)
                .scopeId("proj")
                .subjectId(AGENT)
                .projectRole(role)
                .createdBy(createdBy)
                .build();
    }
}
