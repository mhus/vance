package de.mhus.vance.brain.roles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.MaximegalonType;
import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.api.roles.TenantRole;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.role.RoleAssignmentDocument;
import de.mhus.vance.shared.role.RoleAssignmentService;
import de.mhus.vance.shared.role.request.RoleRequestDocument;
import de.mhus.vance.shared.role.request.RoleRequestOperation;
import de.mhus.vance.shared.role.request.RoleRequestService;
import de.mhus.vance.shared.role.request.RoleRequestStatus;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The whole value of these tools is what they do <em>not</em> do: no call path
 * from here changes a role.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoleRequestToolsTest {

    private static final String TENANT = "acme";
    private static final String SUBJECT = "_trillian-03725";

    @Mock
    RoleRequestService requests;

    @Mock
    RoleAssignmentService assignments;

    @Mock
    MaximegalonService inboxItemService;

    @Mock
    ToolInvocationContext ctx;

    RoleRequestGrantTool grantTool;
    RoleRequestRevokeTool revokeTool;

    @BeforeEach
    void setUp() {
        RoleRequestSupport support = new RoleRequestSupport(requests, assignments, inboxItemService);
        grantTool = new RoleRequestGrantTool(support);
        revokeTool = new RoleRequestRevokeTool(support);

        when(ctx.tenantId()).thenReturn(TENANT);
        when(ctx.userId()).thenReturn("road.runner");
        when(ctx.projectId()).thenReturn("trillian-test");
        when(ctx.processId()).thenReturn("proc-1");
        when(ctx.sessionId()).thenReturn("sess-1");

        when(requests.request(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> RoleRequestDocument.builder()
                        .id("req-1")
                        .tenantId(TENANT)
                        .operation(inv.getArgument(1))
                        .status(RoleRequestStatus.PENDING)
                        .build());
        when(inboxItemService.create(any())).thenAnswer(inv -> {
            MaximegalonDocument doc = inv.getArgument(0);
            doc.setId("item-1");
            return doc;
        });
        when(assignments.forScope(TENANT, RoleScopeType.PROJECT, "test1"))
                .thenReturn(List.of(projectAdmin("zaphod"), projectAdmin("marvin.acme")));
    }

    @Test
    void grantRequest_changesNoRole() {
        grantTool.invoke(grantParams(), ctx);

        // The single most important assertion in this file.
        verify(assignments, never()).setProjectRole(any(), any(), any(), any(), any(), any());
        verify(assignments, never()).setTenantRole(any(), any(), any(), any());
        verify(assignments, never()).remove(any(), any(), any(), any(), any());
    }

    @Test
    void revokeRequest_changesNoRole() {
        revokeTool.invoke(revokeParams(), ctx);

        verify(assignments, never()).remove(any(), any(), any(), any(), any());
        verify(assignments, never()).setProjectRole(any(), any(), any(), any(), any(), any());
    }

    @Test
    void grantRequest_recordsTheRequestAndRoutesAnApprovalItem() {
        Map<String, Object> out = grantTool.invoke(grantParams(), ctx);

        verify(requests)
                .request(
                        TENANT,
                        RoleRequestOperation.GRANT,
                        RoleRequestService.Target.projectGrant(
                                "test1", RoleSubjectType.USER, SUBJECT, ProjectRole.MEMBER),
                        "worker needs access",
                        "road.runner",
                        "proc-1");
        verify(requests).attachInboxItem("req-1", "item-1");
        assertThat(out).containsEntry("applied", false).containsEntry("requestId", "req-1");
    }

    @Test
    void tenantGrant_forATeam_isRefused() {
        Map<String, Object> params = grantParams();
        params.put("scopeType", "TENANT");
        params.put("subjectType", "TEAM");
        params.put("role", "CREATOR");

        assertThatThrownBy(() -> grantTool.invoke(params, ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("users only");
    }

    @Test
    void approvalItem_isNeverLowCriticality_andCarriesTheEffect() {
        grantTool.invoke(grantParams(), ctx);

        ArgumentCaptor<MaximegalonDocument> item = ArgumentCaptor.forClass(MaximegalonDocument.class);
        verify(inboxItemService).create(item.capture());
        // A LOW item with a default auto-answers — a rights change must never
        // be decided by a default.
        assertThat(item.getValue().getCriticality()).isNotEqualTo(Criticality.LOW);
        assertThat(item.getValue().getType()).isEqualTo(MaximegalonType.APPROVAL);
        assertThat(item.getValue().isRequiresAction()).isTrue();
        assertThat(item.getValue().getEffectType()).isEqualTo(RoleRequestEffect.EFFECT_TYPE);
        assertThat(item.getValue().getEffectRef()).isEqualTo("req-1");
    }

    @Test
    void statedReason_isQuotedAsAnUnverifiedClaim() {
        grantTool.invoke(grantParams(), ctx);

        ArgumentCaptor<MaximegalonDocument> item = ArgumentCaptor.forClass(MaximegalonDocument.class);
        verify(inboxItemService).create(item.capture());
        assertThat(item.getValue().getBody()).contains("not verified").contains("> worker needs access");
    }

    @Test
    void deciderChoice_isDeterministic() {
        grantTool.invoke(grantParams(), ctx);

        ArgumentCaptor<MaximegalonDocument> item = ArgumentCaptor.forClass(MaximegalonDocument.class);
        verify(inboxItemService).create(item.capture());
        assertThat(item.getValue().getAssignedToUserId()).isEqualTo("marvin.acme");
    }

    @Test
    void noProjectAdmin_fallsBackToTenantAdmins() {
        when(assignments.forScope(TENANT, RoleScopeType.PROJECT, "test1")).thenReturn(List.of());
        when(assignments.usersWithTenantRole(TENANT, TenantRole.ADMIN)).thenReturn(List.of("ford.prefect"));

        grantTool.invoke(grantParams(), ctx);

        ArgumentCaptor<MaximegalonDocument> item = ArgumentCaptor.forClass(MaximegalonDocument.class);
        verify(inboxItemService).create(item.capture());
        assertThat(item.getValue().getAssignedToUserId()).isEqualTo("ford.prefect");
    }

    @Test
    void tenantAdminRequest_goesToATenantAdminOnly() {
        Map<String, Object> params = grantParams();
        params.put("scopeType", "TENANT");
        params.put("role", "TENANT_ADMIN");
        when(assignments.usersWithTenantRole(TENANT, TenantRole.ADMIN)).thenReturn(List.of("aaa.admin"));
        when(assignments.usersWithTenantRole(TENANT, TenantRole.TENANT_ADMIN)).thenReturn(List.of("root"));

        grantTool.invoke(params, ctx);

        ArgumentCaptor<MaximegalonDocument> item = ArgumentCaptor.forClass(MaximegalonDocument.class);
        verify(inboxItemService).create(item.capture());
        assertThat(item.getValue().getAssignedToUserId()).isEqualTo("root");
    }

    @Test
    void revokingATenantAdmin_goesToATenantAdminOnly() {
        Map<String, Object> params = revokeParams();
        params.put("scopeType", "TENANT");
        params.put("scopeId", TENANT);
        when(assignments.tenantRoleOf(TENANT, SUBJECT)).thenReturn(TenantRole.TENANT_ADMIN);
        when(assignments.usersWithTenantRole(TENANT, TenantRole.ADMIN)).thenReturn(List.of("aaa.admin"));
        when(assignments.usersWithTenantRole(TENANT, TenantRole.TENANT_ADMIN)).thenReturn(List.of("root"));

        revokeTool.invoke(params, ctx);

        // An ADMIN's approval would be refused by the effect — the item would
        // fail without a TENANT_ADMIN ever seeing it.
        ArgumentCaptor<MaximegalonDocument> item = ArgumentCaptor.forClass(MaximegalonDocument.class);
        verify(inboxItemService).create(item.capture());
        assertThat(item.getValue().getAssignedToUserId()).isEqualTo("root");
    }

    @Test
    void nobodyCanDecide_createsNoItemAndSaysSo() {
        when(assignments.forScope(any(), any(), any())).thenReturn(List.of());

        Map<String, Object> out = grantTool.invoke(grantParams(), ctx);

        verify(inboxItemService, never()).create(any());
        assertThat((String) out.get("note")).contains("No administrator");
    }

    @Test
    void teamProjectAdmins_areNotUsedAsDeciders() {
        RoleAssignmentDocument teamAdmin = projectAdmin("ops-team");
        teamAdmin.setSubjectType(RoleSubjectType.TEAM);
        when(assignments.forScope(TENANT, RoleScopeType.PROJECT, "test1")).thenReturn(List.of(teamAdmin));

        grantTool.invoke(grantParams(), ctx);

        // Resolving a team to its members is a wider question; an item can
        // only be assigned to one person.
        verify(inboxItemService, never()).create(any());
    }

    @Test
    void reusedRequest_doesNotCreateASecondItem() {
        when(requests.request(any(), any(), any(), any(), any(), any()))
                .thenReturn(RoleRequestDocument.builder()
                        .id("req-1")
                        .tenantId(TENANT)
                        .status(RoleRequestStatus.PENDING)
                        .inboxItemId("item-existing")
                        .build());

        Map<String, Object> out = grantTool.invoke(grantParams(), ctx);

        verify(inboxItemService, never()).create(any());
        assertThat(out).containsEntry("itemId", "item-existing");
        assertThat((String) out.get("note")).contains("already awaiting approval");
    }

    @Test
    void revoke_carriesNoRole() {
        revokeTool.invoke(revokeParams(), ctx);

        verify(requests)
                .request(
                        eq(TENANT),
                        eq(RoleRequestOperation.REVOKE),
                        eq(RoleRequestService.Target.revoke(
                                RoleScopeType.PROJECT, "test1", RoleSubjectType.USER, SUBJECT)),
                        any(),
                        any(),
                        any());
    }

    private static Map<String, Object> grantParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("scopeType", "PROJECT");
        params.put("scopeId", "test1");
        params.put("subjectType", "USER");
        params.put("subjectId", SUBJECT);
        params.put("role", "MEMBER");
        params.put("reason", "worker needs access");
        return params;
    }

    private static Map<String, Object> revokeParams() {
        Map<String, Object> params = grantParams();
        params.remove("role");
        return params;
    }

    private static RoleAssignmentDocument projectAdmin(String subjectId) {
        return RoleAssignmentDocument.builder()
                .tenantId(TENANT)
                .scopeType(RoleScopeType.PROJECT)
                .scopeId("test1")
                .subjectType(RoleSubjectType.USER)
                .subjectId(subjectId)
                .projectRole(ProjectRole.ADMIN)
                .build();
    }
}
