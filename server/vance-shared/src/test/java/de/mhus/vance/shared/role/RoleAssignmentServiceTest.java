package de.mhus.vance.shared.role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.api.roles.TenantRole;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/** Storage, upsert idempotency and cache invalidation of {@link RoleAssignmentService}. */
class RoleAssignmentServiceTest {

    private final RoleAssignmentRepository repo = mock(RoleAssignmentRepository.class);
    private final RoleAssignmentService service = new RoleAssignmentService(repo);

    @Test
    void forScope_secondCallHitsTheCache() {
        when(repo.findByTenantIdAndScopeTypeAndScopeId("acme", RoleScopeType.PROJECT, "proj"))
                .thenReturn(List.of(member("alice", ProjectRole.MEMBER)));

        service.forScope("acme", RoleScopeType.PROJECT, "proj");
        service.forScope("acme", RoleScopeType.PROJECT, "proj");

        verify(repo, times(1)).findByTenantIdAndScopeTypeAndScopeId("acme", RoleScopeType.PROJECT, "proj");
    }

    @Test
    void setProjectRole_persistsAndInvalidatesTheScope() {
        when(repo.findByTenantIdAndScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findByTenantIdAndScopeTypeAndScopeId("acme", RoleScopeType.PROJECT, "proj"))
                .thenReturn(List.of());
        service.forScope("acme", RoleScopeType.PROJECT, "proj");

        RoleAssignmentDocument saved =
                service.setProjectRole("acme", "proj", RoleSubjectType.USER, "alice", ProjectRole.MEMBER, "root");

        assertThat(saved.getProjectRole()).isEqualTo(ProjectRole.MEMBER);
        assertThat(saved.getTenantRole()).isNull();
        assertThat(saved.getCreatedBy()).isEqualTo("root");
        service.forScope("acme", RoleScopeType.PROJECT, "proj");
        verify(repo, times(2)).findByTenantIdAndScopeTypeAndScopeId("acme", RoleScopeType.PROJECT, "proj");
    }

    @Test
    void setProjectRole_existingRow_takesRoleAndNewAuthor() {
        RoleAssignmentDocument existing = member("alice", ProjectRole.READER);
        existing.setId("r1");
        existing.setCreatedBy(RoleBootstrap.CREATED_BY);
        when(repo.findByTenantIdAndScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(
                        eq("acme"), eq(RoleScopeType.PROJECT), eq("proj"), eq(RoleSubjectType.USER), eq("alice")))
                .thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RoleAssignmentDocument saved =
                service.setProjectRole("acme", "proj", RoleSubjectType.USER, "alice", ProjectRole.ADMIN, "marvin");

        assertThat(saved.getId()).isEqualTo("r1");
        assertThat(saved.getProjectRole()).isEqualTo(ProjectRole.ADMIN);
        assertThat(saved.getCreatedBy())
                .as("a deliberate change makes the row the changer's — a re-seed must leave it alone")
                .isEqualTo("marvin");
    }

    @Test
    void setTenantRole_user_removesTheStoredRow() {
        RoleAssignmentDocument stored = RoleAssignmentDocument.builder()
                .tenantId("acme")
                .scopeType(RoleScopeType.TENANT)
                .scopeId("acme")
                .subjectId("alice")
                .tenantRole(TenantRole.ADMIN)
                .build();
        when(repo.findByTenantIdAndScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(
                        "acme", RoleScopeType.TENANT, "acme", RoleSubjectType.USER, "alice"))
                .thenReturn(Optional.of(stored));

        service.setTenantRole("acme", "alice", TenantRole.USER, "root");

        verify(repo).delete(stored);
        verify(repo, never()).save(any());
    }

    @Test
    void tenantRoleOf_defaultsToUser() {
        when(repo.findByTenantIdAndScopeTypeAndScopeId("acme", RoleScopeType.TENANT, "acme"))
                .thenReturn(List.of(RoleAssignmentDocument.builder()
                        .tenantId("acme")
                        .scopeType(RoleScopeType.TENANT)
                        .scopeId("acme")
                        .subjectId("root")
                        .tenantRole(TenantRole.TENANT_ADMIN)
                        .build()));

        assertThat(service.tenantRoleOf("acme", "root")).isEqualTo(TenantRole.TENANT_ADMIN);
        assertThat(service.tenantRoleOf("acme", "bob")).isEqualTo(TenantRole.USER);
        assertThat(service.usersWithTenantRole("acme", TenantRole.TENANT_ADMIN)).containsExactly("root");
    }

    @Test
    void remove_absentRow_isNoOp() {
        when(repo.findByTenantIdAndScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThat(service.remove("acme", RoleScopeType.PROJECT, "proj", RoleSubjectType.USER, "ghost"))
                .isFalse();
        verify(repo, never()).delete(any());
    }

    @Test
    void set_losingTheInsertRace_retriesOntoTheOtherWritersRow() {
        // Two pods booting against the same fresh database both seed the admin:
        // our read finds nothing, the other writer inserts, role_key_idx rejects
        // our save. The retry has to find their row.
        RoleAssignmentDocument concurrent = member("alice", ProjectRole.READER);
        concurrent.setId("existing-id");
        when(repo.findByTenantIdAndScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(concurrent));
        when(repo.save(any()))
                .thenThrow(new DuplicateKeyException("role_key_idx"))
                .thenAnswer(inv -> inv.getArgument(0));

        RoleAssignmentDocument result = service.setProjectRole(
                "acme", "proj", RoleSubjectType.USER, "alice", ProjectRole.ADMIN, RoleBootstrap.CREATED_BY);

        assertThat(result.getId()).isEqualTo("existing-id");
        assertThat(result.getProjectRole()).isEqualTo(ProjectRole.ADMIN);
    }

    @Test
    void set_failingTwice_isNotARaceAndPropagates() {
        when(repo.findByTenantIdAndScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repo.save(any())).thenThrow(new DuplicateKeyException("role_key_idx"));

        assertThatThrownBy(() -> service.setProjectRole(
                        "acme", "proj", RoleSubjectType.USER, "alice", ProjectRole.ADMIN, RoleBootstrap.CREATED_BY))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private static RoleAssignmentDocument member(String user, ProjectRole role) {
        return RoleAssignmentDocument.builder()
                .tenantId("acme")
                .scopeType(RoleScopeType.PROJECT)
                .scopeId("proj")
                .subjectType(RoleSubjectType.USER)
                .subjectId(user)
                .projectRole(role)
                .build();
    }
}
