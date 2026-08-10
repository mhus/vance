package de.mhus.vance.simpleauth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SimpleAuthPermissionBootstrapTest {

    @Mock
    PermissionGrantService grants;

    @Mock
    PermissionRequestService requests;

    @InjectMocks
    SimpleAuthPermissionBootstrap bootstrap;

    @Test
    void revokeAll_subjectHoldsGrantsOnSeveralScopes_removesEveryOne() {
        when(grants.forSubject("acme", GrantSubjectType.USER, "_trillian-03725"))
                .thenReturn(List.of(
                        grant(GrantScopeType.PROJECT, "trillian-test"),
                        grant(GrantScopeType.PROJECT, "test1"),
                        grant(GrantScopeType.TENANT, "acme")));
        when(grants.remove(any(), any(), any(), any(), any())).thenReturn(true);

        bootstrap.revokeAll("acme", "_trillian-03725");

        verify(grants).remove("acme", GrantScopeType.PROJECT, "trillian-test",
                GrantSubjectType.USER, "_trillian-03725");
        verify(grants).remove("acme", GrantScopeType.PROJECT, "test1",
                GrantSubjectType.USER, "_trillian-03725");
        verify(grants).remove("acme", GrantScopeType.TENANT, "acme",
                GrantSubjectType.USER, "_trillian-03725");
    }

    @Test
    void revokeAll_subjectHasNoGrants_isNoOp() {
        when(grants.forSubject("acme", GrantSubjectType.USER, "ghost"))
                .thenReturn(List.of());

        bootstrap.revokeAll("acme", "ghost");

        verify(grants, never()).remove(any(), any(), any(), any(), any());
    }

    @Test
    void revokeAll_leavesOtherSubjectsAlone() {
        when(grants.forSubject("acme", GrantSubjectType.USER, "_trillian-03725"))
                .thenReturn(List.of(grant(GrantScopeType.PROJECT, "trillian-test")));
        when(grants.remove(any(), any(), any(), any(), any())).thenReturn(true);

        bootstrap.revokeAll("acme", "_trillian-03725");

        // The subject is passed explicitly on every removal — a grant document
        // belonging to someone else could never be dropped through this path.
        verify(grants, never()).remove(any(), any(), any(),
                eq(GrantSubjectType.TEAM), any());
        verify(grants).remove("acme", GrantScopeType.PROJECT, "trillian-test",
                GrantSubjectType.USER, "_trillian-03725");
    }

    @Test
    void revokeAll_alsoExpiresPendingRequestsOfThatSubject() {
        when(grants.forSubject("acme", GrantSubjectType.USER, "_trillian-03725"))
                .thenReturn(List.of());

        bootstrap.revokeAll("acme", "_trillian-03725");

        // A request naming a deleted account could otherwise be approved
        // onto a later account that reuses the name.
        verify(requests).expireForSubject("acme", GrantSubjectType.USER, "_trillian-03725");
    }

    @Test
    void grantProjectAdmin_writesAdminRoleOnProjectScope() {
        bootstrap.grantProjectAdmin("acme", "trillian-test", "_trillian-03725");

        verify(grants).set(eq("acme"), eq(GrantScopeType.PROJECT), eq("trillian-test"),
                eq(GrantSubjectType.USER), eq("_trillian-03725"),
                eq(GrantRole.ADMIN), any());
    }

    @Test
    void revokeAll_grantAlreadyGoneConcurrently_doesNotFail() {
        when(grants.forSubject("acme", GrantSubjectType.USER, "_trillian-03725"))
                .thenReturn(List.of(grant(GrantScopeType.PROJECT, "trillian-test")));
        when(grants.remove(any(), any(), any(), any(), any())).thenReturn(false);

        assertThatCode(() -> bootstrap.revokeAll("acme", "_trillian-03725"))
                .doesNotThrowAnyException();
    }

    private static PermissionGrantDocument grant(GrantScopeType scopeType, String scopeId) {
        PermissionGrantDocument doc = new PermissionGrantDocument();
        doc.setTenantId("acme");
        doc.setScopeType(scopeType);
        doc.setScopeId(scopeId);
        doc.setSubjectType(GrantSubjectType.USER);
        doc.setSubjectId("_trillian-03725");
        doc.setRole(GrantRole.ADMIN);
        return doc;
    }
}
