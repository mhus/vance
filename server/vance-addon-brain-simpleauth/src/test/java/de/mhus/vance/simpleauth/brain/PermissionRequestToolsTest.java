package de.mhus.vance.simpleauth.brain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.inbox.InboxItemService;
import de.mhus.vance.simpleauth.GrantRole;
import de.mhus.vance.simpleauth.GrantScopeType;
import de.mhus.vance.simpleauth.GrantSubjectType;
import de.mhus.vance.simpleauth.PermissionGrantDocument;
import de.mhus.vance.simpleauth.PermissionGrantService;
import de.mhus.vance.simpleauth.PermissionRequestDocument;
import de.mhus.vance.simpleauth.PermissionRequestOperation;
import de.mhus.vance.simpleauth.PermissionRequestService;
import de.mhus.vance.simpleauth.PermissionRequestStatus;
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
 * The whole value of these tools is what they do <em>not</em> do: no call
 * path from here reaches {@link PermissionGrantService}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PermissionRequestToolsTest {

    private static final String TENANT = "acme";

    @Mock
    PermissionRequestService requests;
    @Mock
    PermissionGrantService grants;
    @Mock
    InboxItemService inboxItemService;
    @Mock
    ToolInvocationContext ctx;

    PermissionRequestGrantTool grantTool;
    PermissionRequestRevokeTool revokeTool;

    @BeforeEach
    void setUp() {
        PermissionRequestSupport support =
                new PermissionRequestSupport(requests, grants, inboxItemService);
        grantTool = new PermissionRequestGrantTool(support);
        revokeTool = new PermissionRequestRevokeTool(support);

        when(ctx.tenantId()).thenReturn(TENANT);
        when(ctx.userId()).thenReturn("road.runner");
        when(ctx.projectId()).thenReturn("trillian-test");
        when(ctx.processId()).thenReturn("proc-1");
        when(ctx.sessionId()).thenReturn("sess-1");

        when(requests.request(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> PermissionRequestDocument.builder()
                        .id("req-1")
                        .tenantId(TENANT)
                        .operation(inv.getArgument(1))
                        .status(PermissionRequestStatus.PENDING)
                        .build());
        when(inboxItemService.create(any())).thenAnswer(inv -> {
            InboxItemDocument doc = inv.getArgument(0);
            doc.setId("item-1");
            return doc;
        });
        when(grants.forScope(TENANT, GrantScopeType.PROJECT, "test1"))
                .thenReturn(List.of(adminGrant("zaphod"), adminGrant("marvin.acme")));
    }

    @Test
    void grantRequest_writesNoGrant() {
        grantTool.invoke(grantParams(), ctx);

        // The single most important assertion in this file.
        verify(grants, never()).set(any(), any(), any(), any(), any(), any(), any());
        verify(grants, never()).remove(any(), any(), any(), any(), any());
    }

    @Test
    void revokeRequest_writesNoGrant() {
        revokeTool.invoke(revokeParams(), ctx);

        verify(grants, never()).remove(any(), any(), any(), any(), any());
        verify(grants, never()).set(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void grantRequest_recordsTheRequestAndRoutesAnApprovalItem() {
        Map<String, Object> out = grantTool.invoke(grantParams(), ctx);

        verify(requests).request(eqTenant(), eq(PermissionRequestOperation.GRANT),
                eq(GrantScopeType.PROJECT), eq("test1"), eq(GrantSubjectType.USER),
                eq("_trillian-03725"), eq(GrantRole.WRITER), eq("worker needs access"),
                eq("road.runner"), eq("proc-1"));
        verify(requests).attachInboxItem("req-1", "item-1");
        assertThat(out).containsEntry("applied", false).containsEntry("requestId", "req-1");
    }

    @Test
    void approvalItem_isNeverLowCriticality() {
        grantTool.invoke(grantParams(), ctx);

        ArgumentCaptor<InboxItemDocument> item =
                ArgumentCaptor.forClass(InboxItemDocument.class);
        verify(inboxItemService).create(item.capture());
        // A LOW item with a default auto-answers — a rights change must
        // never be decided by a default.
        assertThat(item.getValue().getCriticality()).isNotEqualTo(Criticality.LOW);
        assertThat(item.getValue().getType()).isEqualTo(InboxItemType.APPROVAL);
        assertThat(item.getValue().isRequiresAction()).isTrue();
    }

    @Test
    void approvalItem_carriesTheEffectWiring() {
        grantTool.invoke(grantParams(), ctx);

        ArgumentCaptor<InboxItemDocument> item =
                ArgumentCaptor.forClass(InboxItemDocument.class);
        verify(inboxItemService).create(item.capture());
        assertThat(item.getValue().getEffectType())
                .isEqualTo(PermissionRequestEffect.EFFECT_TYPE);
        assertThat(item.getValue().getEffectRef()).isEqualTo("req-1");
    }

    @Test
    void statedReason_isQuotedAsAnUnverifiedClaim() {
        grantTool.invoke(grantParams(), ctx);

        ArgumentCaptor<InboxItemDocument> item =
                ArgumentCaptor.forClass(InboxItemDocument.class);
        verify(inboxItemService).create(item.capture());
        // The reason is LLM-written and may echo injected text — it must
        // not read as the system's own account of what will happen.
        assertThat(item.getValue().getBody())
                .contains("not verified")
                .contains("> worker needs access");
    }

    @Test
    void deciderChoice_isDeterministic() {
        grantTool.invoke(grantParams(), ctx);

        ArgumentCaptor<InboxItemDocument> item =
                ArgumentCaptor.forClass(InboxItemDocument.class);
        verify(inboxItemService).create(item.capture());
        // Sorted, so the same situation always routes the same way rather
        // than following storage order.
        assertThat(item.getValue().getAssignedToUserId()).isEqualTo("marvin.acme");
    }

    @Test
    void noAdminOnScope_fallsBackToTenantAdmins() {
        when(grants.forScope(TENANT, GrantScopeType.PROJECT, "test1")).thenReturn(List.of());
        when(grants.forScope(TENANT, GrantScopeType.TENANT, TENANT))
                .thenReturn(List.of(adminGrant("ford.prefect")));

        grantTool.invoke(grantParams(), ctx);

        ArgumentCaptor<InboxItemDocument> item =
                ArgumentCaptor.forClass(InboxItemDocument.class);
        verify(inboxItemService).create(item.capture());
        assertThat(item.getValue().getAssignedToUserId()).isEqualTo("ford.prefect");
    }

    @Test
    void nobodyCanDecide_createsNoItemAndSaysSo() {
        when(grants.forScope(any(), any(), any())).thenReturn(List.of());

        Map<String, Object> out = grantTool.invoke(grantParams(), ctx);

        verify(inboxItemService, never()).create(any());
        assertThat(out).containsEntry("applied", false);
        assertThat((String) out.get("note")).contains("No administrator");
    }

    @Test
    void teamAdminGrants_areNotUsedAsDeciders() {
        PermissionGrantDocument teamAdmin = adminGrant("ops-team");
        teamAdmin.setSubjectType(GrantSubjectType.TEAM);
        when(grants.forScope(TENANT, GrantScopeType.PROJECT, "test1"))
                .thenReturn(List.of(teamAdmin));
        when(grants.forScope(TENANT, GrantScopeType.TENANT, TENANT)).thenReturn(List.of());

        grantTool.invoke(grantParams(), ctx);

        // Resolving a team to its members is a wider question; an item can
        // only be assigned to one person.
        verify(inboxItemService, never()).create(any());
    }

    @Test
    void reusedRequest_doesNotCreateASecondItem() {
        when(requests.request(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(PermissionRequestDocument.builder()
                        .id("req-1")
                        .tenantId(TENANT)
                        .status(PermissionRequestStatus.PENDING)
                        .inboxItemId("item-existing")
                        .build());

        Map<String, Object> out = grantTool.invoke(grantParams(), ctx);

        verify(inboxItemService, never()).create(any());
        assertThat(out).containsEntry("itemId", "item-existing");
        assertThat((String) out.get("note")).contains("already awaiting approval");
    }

    // ──── helpers ───────────────────────────────────────────────────────

    private static Map<String, Object> grantParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("scopeType", "PROJECT");
        params.put("scopeId", "test1");
        params.put("subjectType", "USER");
        params.put("subjectId", "_trillian-03725");
        params.put("role", "WRITER");
        params.put("reason", "worker needs access");
        return params;
    }

    private static Map<String, Object> revokeParams() {
        Map<String, Object> params = grantParams();
        params.remove("role");
        return params;
    }

    private static PermissionGrantDocument adminGrant(String subjectId) {
        return PermissionGrantDocument.builder()
                .tenantId(TENANT)
                .scopeType(GrantScopeType.PROJECT)
                .scopeId("test1")
                .subjectType(GrantSubjectType.USER)
                .subjectId(subjectId)
                .role(GrantRole.ADMIN)
                .build();
    }

    private static String eqTenant() {
        return org.mockito.ArgumentMatchers.eq(TENANT);
    }

    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
