package de.mhus.vance.brain.roles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.api.roles.TenantRole;
import de.mhus.vance.brain.tools.eddie.AdminToolSupport;
import de.mhus.vance.shared.activity.EddieActivityKind;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.role.RoleAdministrationService;
import de.mhus.vance.shared.role.RoleAssignmentException;
import de.mhus.vance.shared.role.request.RoleRequestOperation;
import de.mhus.vance.shared.role.request.RoleRequestService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A role change goes through directly when the caller's own authority covers
 * it, and becomes a request exactly when it does not — never for a broken
 * rule, which stays the caller's error.
 */
class RoleChangeSupportTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "_user_alice", "s", "p", "alice");
    private static final SecurityContext ALICE = SecurityContext.user("alice", "acme", List.of());

    private RoleAdministrationService administration;
    private RoleRequestSupport requestSupport;
    private AdminToolSupport admin;
    private RoleChangeSupport support;

    @BeforeEach
    void setUp() {
        administration = mock(RoleAdministrationService.class);
        requestSupport = mock(RoleRequestSupport.class);
        admin = mock(AdminToolSupport.class);
        when(admin.actor(any(), anyString())).thenReturn(ALICE);
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("requested", true);
        receipt.put("applied", false);
        when(requestSupport.raise(any(), any(), any(), any())).thenReturn(receipt);
        support = new RoleChangeSupport(administration, requestSupport, admin);
    }

    private static RoleRequestService.Target projectGrant() {
        return RoleRequestService.Target.projectGrant("research", RoleSubjectType.USER, "bob", ProjectRole.MEMBER);
    }

    @Test
    void withinAuthority_isAppliedDirectly() {
        Map<String, Object> out = support.grant(CTX, "role_grant", projectGrant(), null);

        verify(administration)
                .assignProjectRole(ALICE, "acme", "research", RoleSubjectType.USER, "bob", ProjectRole.MEMBER);
        verify(requestSupport, never()).raise(any(), any(), any(), any());
        verify(admin).record(eq(CTX), eq(EddieActivityKind.ROLE_CHANGED), anyString(), any());
        assertThat(out).containsEntry("applied", true).containsEntry("role", "MEMBER");
    }

    @Test
    void aboveOwnRole_becomesARequest() {
        RoleRequestService.Target target =
                RoleRequestService.Target.tenantGrant("acme", "bob", TenantRole.TENANT_ADMIN);
        doThrow(new RoleAssignmentException(RoleAssignmentException.Kind.BEYOND_AUTHORITY, "above your own role"))
                .when(administration)
                .assignTenantRole(ALICE, "acme", "bob", TenantRole.TENANT_ADMIN);

        Map<String, Object> out = support.grant(CTX, "role_grant", target, "needs to set up SSO");

        verify(requestSupport).raise(CTX, RoleRequestOperation.GRANT, target, "needs to set up SSO");
        assertThat(out)
                .containsEntry("requested", true)
                .containsEntry("applied", false)
                .containsEntry("beyondYourAuthority", "above your own role");
        verify(admin, never()).record(any(), any(), anyString(), any());
    }

    @Test
    void noProjectAdmin_becomesARequest() {
        doThrow(new PermissionDeniedException(ALICE, new Resource.Project("acme", "research"), Action.ADMIN))
                .when(administration)
                .assignProjectRole(any(), any(), any(), any(), any(), any());

        Map<String, Object> out = support.grant(CTX, "role_grant", projectGrant(), null);

        assertThat(out).containsEntry("requested", true);
    }

    @Test
    void brokenRule_isAnErrorNotARequest() {
        RoleRequestService.Target target = RoleRequestService.Target.tenantGrant("acme", "root", TenantRole.USER);
        doThrow(new RoleAssignmentException(RoleAssignmentException.Kind.REFUSED, "last TENANT_ADMIN"))
                .when(administration)
                .assignTenantRole(ALICE, "acme", "root", TenantRole.USER);

        assertThatThrownBy(() -> support.grant(CTX, "role_grant", target, null))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("last TENANT_ADMIN");
        verify(requestSupport, never()).raise(any(), any(), any(), any());
    }

    @Test
    void revokeTenantRole_fallsBackToUser() {
        RoleRequestService.Target target =
                RoleRequestService.Target.revoke(RoleScopeType.TENANT, "acme", RoleSubjectType.USER, "bob");

        Map<String, Object> out = support.revoke(CTX, "role_revoke", target, null);

        verify(administration).assignTenantRole(ALICE, "acme", "bob", TenantRole.USER);
        assertThat(out).containsEntry("applied", true).containsEntry("role", "USER");
    }

    @Test
    void revokeMissingMembership_saysNothingChanged() {
        RoleRequestService.Target target =
                RoleRequestService.Target.revoke(RoleScopeType.PROJECT, "research", RoleSubjectType.TEAM, "infra");
        when(administration.removeProjectRole(ALICE, "acme", "research", RoleSubjectType.TEAM, "infra"))
                .thenReturn(false);

        Map<String, Object> out = support.revoke(CTX, "role_revoke", target, null);

        assertThat(out).containsKey("note");
        verify(admin, never()).record(any(), any(), anyString(), any());
    }

    @Test
    void teamTenantRole_isRefusedBeforeAnything() {
        RoleRequestService.Target target =
                RoleRequestService.Target.revoke(RoleScopeType.TENANT, "acme", RoleSubjectType.TEAM, "infra");

        assertThatThrownBy(() -> support.revoke(CTX, "role_revoke", target, null))
                .isInstanceOf(ToolException.class);
        verify(administration, never()).assignTenantRole(any(), any(), any(), any());
    }
}
