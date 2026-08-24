package de.mhus.vance.brain.tools.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.inbox.InboxAuthz;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The gate in front of both tool families. Two properties here are the ones a
 * mistake would not announce: a headless process must not fall through to
 * SYSTEM (which passes every check), and an invisible thread must not be
 * distinguishable from an absent one.
 */
class InboxToolSupportTest {

    private static final String TENANT = "acme";

    private MaximegalonService threads;
    private PermissionService permissionService;
    private SecurityContextFactory contextFactory;
    private InboxAuthz authz;
    private InboxToolSupport support;

    @BeforeEach
    void setUp() {
        threads = mock(MaximegalonService.class);
        permissionService = mock(PermissionService.class);
        contextFactory = mock(SecurityContextFactory.class);
        authz = mock(InboxAuthz.class);
        support = new InboxToolSupport(threads, permissionService, contextFactory, authz);
        when(contextFactory.forToolSubject(any(), any()))
                .thenReturn(SecurityContext.user("wile.coyote", TENANT, java.util.List.of()));
    }

    private static ToolInvocationContext ctx(String userId) {
        ToolInvocationContext c = mock(ToolInvocationContext.class);
        when(c.tenantId()).thenReturn(TENANT);
        when(c.userId()).thenReturn(userId);
        return c;
    }

    @Test
    void ownerOrThrow_withoutUser_refusesInsteadOfActingAsSystem() {
        // A system session resolves to SecurityContext.SYSTEM, which passes R1 and
        // would hand back every thread in the tenant. There is no person here, so
        // the answer is "nothing to read" — not "everything".
        assertThatThrownBy(() -> support.ownerOrThrow(ctx(null)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("no user bound");

        assertThatThrownBy(() -> support.ownerOrThrow(ctx("  ")))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void ownerOrThrow_serviceAccount_isAPersonForThisPurpose() {
        assertThat(support.ownerOrThrow(ctx("_daemon-prod-01"))).isEqualTo("_daemon-prod-01");
    }

    @Test
    void loadVisible_withoutUser_neverQueries() {
        assertThatThrownBy(() -> support.loadVisible(TENANT, "t1", ctx(null)))
                .isInstanceOf(ToolException.class);

        verify(threads, never()).findById(any(), any());
    }

    @Test
    void loadVisible_absentThread_saysTheSameAsAnInvisibleOne() {
        when(threads.findById(TENANT, "t1")).thenReturn(Optional.empty());
        MaximegalonDocument other = MaximegalonDocument.builder()
                .id("t2").tenantId(TENANT).assignedToUserId("someone.else").build();
        when(threads.findById(TENANT, "t2")).thenReturn(Optional.of(other));
        when(permissionService.check(any(), any(), any())).thenReturn(false);
        when(authz.maySee(any(), any(), any())).thenReturn(false);

        String absent = catchMessage(() -> support.loadVisible(TENANT, "t1", ctx("wile.coyote")));
        String invisible = catchMessage(() -> support.loadVisible(TENANT, "t2", ctx("wile.coyote")));

        // A distinguishing message would confirm the thread exists.
        assertThat(absent).isEqualTo("no inbox thread 't1' is visible to you.");
        assertThat(invisible).isEqualTo("no inbox thread 't2' is visible to you.");
    }

    @Test
    void loadVisible_participantWithoutProviderGrant_stillGetsIn() {
        // maySee ⊋ mayDecide: an invited participant is in no derivation the
        // provider knows about, so the document check has to be able to let them
        // through after the provider said no.
        MaximegalonDocument doc = MaximegalonDocument.builder()
                .id("t1").tenantId(TENANT).assignedToUserId("someone.else").build();
        when(threads.findById(TENANT, "t1")).thenReturn(Optional.of(doc));
        when(permissionService.check(any(), any(), any())).thenReturn(false);
        when(authz.maySee(TENANT, "wile.coyote", doc)).thenReturn(true);

        assertThat(support.loadVisible(TENANT, "t1", ctx("wile.coyote"))).isSameAs(doc);
    }

    @Test
    void loadVisible_providerGrant_shortCircuitsTheDocumentCheck() {
        MaximegalonDocument doc = MaximegalonDocument.builder()
                .id("t1").tenantId(TENANT).assignedToUserId("wile.coyote").build();
        when(threads.findById(TENANT, "t1")).thenReturn(Optional.of(doc));
        when(permissionService.check(any(), any(), any())).thenReturn(true);

        assertThat(support.loadVisible(TENANT, "t1", ctx("wile.coyote"))).isSameAs(doc);
        verify(authz, never()).maySee(any(), any(), any());
    }

    private static String catchMessage(Runnable r) {
        try {
            r.run();
            throw new AssertionError("expected a ToolException");
        } catch (ToolException e) {
            return e.getMessage();
        }
    }
}
