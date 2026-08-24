package de.mhus.vance.brain.inbox.rest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.inbox.InboxDelegateRequest;
import de.mhus.vance.api.inbox.InboxInviteRequest;
import de.mhus.vance.api.inbox.MaximegalonStatus;
import de.mhus.vance.api.inbox.MaximegalonType;
import de.mhus.vance.brain.inbox.InboxAuthz;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.inbox.InboxEffectRegistry;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.team.TeamService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>Handing a matter to a person spends their attention, so it needs WRITE on
 * their inbox.</b> {@code invite} enforced that from the start; {@code delegate}
 * did not, on either the REST or the WS path — the check on the item only says
 * the caller may settle this thread, never whose desk it may land on.
 *
 * <p>The gap survived because nothing asserted it. Hence this test: the rule is
 * one line at each call site and nobody notices a missing line.
 */
class InboxControllerDeliveryAuthzTest {

    private static final String TENANT = "acme";
    private static final String ME = "wile.coyote";
    private static final String TARGET = "road.runner";

    private MaximegalonService service;
    private RequestAuthority authority;
    private TeamService teamService;
    private InboxController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        service = mock(MaximegalonService.class);
        authority = mock(RequestAuthority.class);
        teamService = mock(TeamService.class);
        when(teamService.byMember(any(), any())).thenReturn(List.of());
        controller = new InboxController(
                service, new InboxEffectRegistry(List.of()), teamService,
                mock(ProjectService.class), authority, new InboxAuthz(teamService));

        request = mock(HttpServletRequest.class);
        when(request.getAttribute(AccessFilterBase.ATTR_USERNAME)).thenReturn(ME);

        MaximegalonDocument mine = MaximegalonDocument.builder()
                .id("t1").tenantId(TENANT)
                .type(MaximegalonType.APPROVAL)
                .status(MaximegalonStatus.PENDING)
                .assignedToUserId(ME)
                .requiresAction(true)
                .build();
        when(service.findById(TENANT, "t1")).thenReturn(Optional.of(mine));
    }

    /** The resource that names "somebody else's inbox", as both call sites build it. */
    private static Resource.InboxItem targetInbox() {
        return new Resource.InboxItem(TENANT, "", TARGET);
    }

    @Test
    void delegate_isRefusedWhenTheCallerMayNotWriteToTheTargetInbox() {
        doThrow(new PermissionDeniedException(
                        de.mhus.vance.shared.permission.SecurityContext.user(ME, TENANT, List.of()),
                        targetInbox(), Action.WRITE))
                .when(authority).enforce(eq(request), eq(targetInbox()), eq(Action.WRITE));

        InboxDelegateRequest body = new InboxDelegateRequest();
        body.setItemId("t1");
        body.setToUserId(TARGET);

        assertThatThrownBy(() -> controller.delegate(TENANT, "t1", body, request))
                .isInstanceOf(PermissionDeniedException.class);

        // The refusal has to land before the write, not after it.
        verify(service, never()).delegate(any(), any(), any(), any(), any());
    }

    @Test
    void delegate_authorizesTheTargetInboxOnTheWayThrough() {
        when(service.delegate(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(MaximegalonDocument.builder()
                        .id("t1").tenantId(TENANT).assignedToUserId(TARGET).build()));

        InboxDelegateRequest body = new InboxDelegateRequest();
        body.setItemId("t1");
        body.setToUserId(TARGET);
        controller.delegate(TENANT, "t1", body, request);

        verify(authority).enforce(eq(request), eq(targetInbox()), eq(Action.WRITE));
    }

    @Test
    void invite_stillAuthorizesTheInvitedPersonsInbox() {
        // The precedent this rule comes from — asserted here so the two stay
        // in step rather than drifting apart again.
        when(service.invite(any(), any(), any(), any()))
                .thenReturn(Optional.of(MaximegalonDocument.builder()
                        .id("t1").tenantId(TENANT).assignedToUserId(ME).build()));

        InboxInviteRequest body = new InboxInviteRequest();
        body.setUserId(TARGET);
        controller.invite(TENANT, "t1", body, request);

        verify(authority).enforce(eq(request), eq(targetInbox()), eq(Action.WRITE));
    }
}
