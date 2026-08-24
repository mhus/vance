package de.mhus.vance.brain.tools.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.inbox.MaximegalonStatus;
import de.mhus.vance.api.inbox.MaximegalonType;
import de.mhus.vance.brain.inbox.InboxAuthz;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Routing a matter to somebody else. Two gates have to hold, and the second is
 * the one that did not exist before: settling this thread is the caller's right,
 * but <em>delivering</em> it needs WRITE on the recipient's inbox. Here the
 * recipient is a raw model parameter, so a missing check would let an agent put
 * work on any desk in the tenant.
 */
class ThreadDelegateToolTest {

    private static final String TENANT = "acme";
    private static final String OWNER = "wile.coyote";
    private static final String TARGET = "road.runner";

    private MaximegalonService threads;
    private InboxAuthz authz;
    private PermissionService permissionService;
    private ThreadDelegateTool tool;

    @BeforeEach
    void setUp() {
        threads = mock(MaximegalonService.class);
        authz = mock(InboxAuthz.class);
        permissionService = mock(PermissionService.class);
        SecurityContextFactory contextFactory = mock(SecurityContextFactory.class);
        when(contextFactory.forToolSubject(any(), any()))
                .thenReturn(SecurityContext.user(OWNER, TENANT, List.of()));
        when(permissionService.check(any(), any(), any())).thenReturn(true);
        tool = new ThreadDelegateTool(threads,
                new InboxToolSupport(threads, permissionService, contextFactory, authz),
                permissionService, contextFactory);

        MaximegalonDocument doc = MaximegalonDocument.builder()
                .id("t1").tenantId(TENANT)
                .type(MaximegalonType.APPROVAL)
                .status(MaximegalonStatus.PENDING)
                .assignedToUserId(OWNER)
                .requiresAction(true)
                .build();
        when(threads.findById(TENANT, "t1")).thenReturn(Optional.of(doc));
        when(authz.mayDecide(TENANT, OWNER, OWNER)).thenReturn(true);
    }

    private static ToolInvocationContext ctx() {
        ToolInvocationContext c = mock(ToolInvocationContext.class);
        when(c.tenantId()).thenReturn(TENANT);
        when(c.userId()).thenReturn(OWNER);
        return c;
    }

    private static Resource.InboxItem targetInbox() {
        return new Resource.InboxItem(TENANT, "", TARGET);
    }

    @Test
    void delegate_handsTheThreadOverAndSaysItIsStillOpen() {
        when(threads.delegate(TENANT, "t1", TARGET, OWNER, "yours"))
                .thenReturn(Optional.of(MaximegalonDocument.builder()
                        .id("t1").assignedToUserId(TARGET).requiresAction(true).build()));

        Map<String, Object> out = tool.invoke(
                Map.of("threadId", "t1", "toUserId", TARGET, "note", "yours"), ctx());

        assertThat(out.get("assignedToUserId")).isEqualTo(TARGET);
        // "delegated" reads like "dealt with"; it is not.
        assertThat(out.get("stillOpen")).isEqualTo(true);
    }

    @Test
    void delegate_withoutWriteOnTheTargetInbox_isRefusedBeforeTheWrite() {
        doThrow(new PermissionDeniedException(
                        SecurityContext.user(OWNER, TENANT, List.of()),
                        targetInbox(), Action.WRITE))
                .when(permissionService).enforce(any(), eq(targetInbox()), eq(Action.WRITE));

        assertThatThrownBy(() -> tool.invoke(
                Map.of("threadId", "t1", "toUserId", TARGET), ctx()))
                .isInstanceOf(PermissionDeniedException.class);

        verify(threads, never()).delegate(any(), any(), any(), any(), any());
    }

    @Test
    void delegate_authorizesTheRecipientsInboxSeparately() {
        when(threads.delegate(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(MaximegalonDocument.builder()
                        .id("t1").assignedToUserId(TARGET).build()));

        tool.invoke(Map.of("threadId", "t1", "toUserId", TARGET), ctx());

        verify(permissionService).enforce(any(), eq(targetInbox()), eq(Action.WRITE));
    }

    @Test
    void delegate_toYourself_isRefusedAsANoOp() {
        assertThatThrownBy(() -> tool.invoke(
                Map.of("threadId", "t1", "toUserId", OWNER), ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("already on your desk");

        verify(threads, never()).delegate(any(), any(), any(), any(), any());
    }

    @Test
    void delegate_readerWhoMayNotSettle_cannotHandItOnEither() {
        when(authz.mayDecide(TENANT, OWNER, OWNER)).thenReturn(false);

        assertThatThrownBy(() -> tool.invoke(
                Map.of("threadId", "t1", "toUserId", TARGET), ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not hand it on");

        verify(threads, never()).delegate(any(), any(), any(), any(), any());
    }

    @Test
    void delegate_withoutUserBound_refusesInsteadOfActingAsSystem() {
        ToolInvocationContext headless = mock(ToolInvocationContext.class);
        when(headless.tenantId()).thenReturn(TENANT);
        when(headless.userId()).thenReturn(null);

        assertThatThrownBy(() -> tool.invoke(
                Map.of("threadId", "t1", "toUserId", TARGET), headless))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("no user bound");
    }
}
