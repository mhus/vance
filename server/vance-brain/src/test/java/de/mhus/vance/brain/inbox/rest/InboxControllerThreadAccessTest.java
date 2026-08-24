package de.mhus.vance.brain.inbox.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.inbox.MaximegalonStatus;
import de.mhus.vance.api.inbox.MaximegalonType;
import de.mhus.vance.brain.inbox.InboxAuthz;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.inbox.InboxEffectRegistry;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.team.TeamService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Who reaches a thread through the REST surface. The controller is built by
 * hand with mocks rather than through MockMvc — what is under test is the
 * access decision, not the HTTP plumbing.
 *
 * <p>This is the one path where a mistake is silent: too much access does not
 * throw, it just quietly answers. Hence tests, even though controller tests are
 * opt-in in this tree.
 */
class InboxControllerThreadAccessTest {

    private MaximegalonService service;
    private RequestAuthority authority;
    private TeamService teamService;
    private InboxController controller;

    @BeforeEach
    void setUp() {
        service = mock(MaximegalonService.class);
        authority = mock(RequestAuthority.class);
        teamService = mock(TeamService.class);
        when(teamService.byMember(any(), any())).thenReturn(List.of());
        controller = new InboxController(
                service, new InboxEffectRegistry(List.of()), teamService,
                mock(ProjectService.class), authority, new InboxAuthz(teamService),
                mock(de.mhus.vance.shared.document.DocumentService.class));
    }

    /**
     * The scenario the participant list exists for: Cecilia was invited, is
     * neither assignee nor a team-mate of one, and the permission provider
     * therefore says no. She still has to be able to read the thread.
     */
    @Test
    void findOne_participantWithoutPermission_isAllowed() {
        MaximegalonDocument doc = thread("cecilia");
        when(service.findById("acme", "t1")).thenReturn(Optional.of(doc));
        when(authority.check(any(HttpServletRequest.class), any(Resource.class),
                any(Action.class))).thenReturn(false);

        assertThat(controller.findOne("acme", "t1", request("cecilia")).getId()).isEqualTo("t1");
    }

    /** Participation is not a universal key — a stranger still gets nothing. */
    @Test
    void findOne_strangerToTheThread_is404() {
        MaximegalonDocument doc = thread("cecilia");
        when(service.findById("acme", "t1")).thenReturn(Optional.of(doc));
        when(authority.check(any(HttpServletRequest.class), any(Resource.class),
                any(Action.class))).thenReturn(false);

        assertThatThrownBy(() -> controller.findOne("acme", "t1", request("eve")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    /**
     * The provider stays the authority: when it allows the request, the
     * document's own participant list is not even consulted.
     */
    @Test
    void findOne_permissionGrantsIt_needsNoParticipation() {
        MaximegalonDocument doc = thread();
        when(service.findById("acme", "t1")).thenReturn(Optional.of(doc));
        when(authority.check(any(HttpServletRequest.class), any(Resource.class),
                any(Action.class))).thenReturn(true);

        assertThat(controller.findOne("acme", "t1", request("dave")).getId()).isEqualTo("t1");
    }

    /**
     * The point of splitting the gate: a participant may contribute but must not
     * be able to settle the matter — answering would also fire the item's
     * effect, which grants permissions.
     */
    @Test
    void answer_byParticipantWhoCannotDecide_is404() {
        MaximegalonDocument doc = thread("cecilia");
        when(service.findById("acme", "t1")).thenReturn(Optional.of(doc));
        when(authority.check(any(HttpServletRequest.class), any(Resource.class),
                any(Action.class))).thenReturn(false);

        assertThatThrownBy(() -> controller.answer("acme", "t1",
                de.mhus.vance.api.inbox.InboxAnswerRequest.builder()
                        .outcome(de.mhus.vance.api.inbox.AnswerOutcome.DECIDED)
                        .build(),
                request("cecilia")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    /** A message from a participant goes through — that is contributing. */
    @Test
    void postMessage_byParticipant_isAllowed() {
        MaximegalonDocument doc = thread("cecilia");
        when(service.findById("acme", "t1")).thenReturn(Optional.of(doc));
        when(authority.check(any(HttpServletRequest.class), any(Resource.class),
                any(Action.class))).thenReturn(false);
        when(service.postMessage("acme", "t1", "cecilia", "here is the number", null))
                .thenReturn(Optional.of(doc));

        assertThat(controller.postMessage("acme", "t1",
                de.mhus.vance.api.inbox.InboxMessagePostRequest.builder()
                        .body("here is the number").build(),
                request("cecilia")).getStatusCode().value()).isEqualTo(200);
    }

    // ──── helpers ───────────────────────────────────────────────────────

    private static MaximegalonDocument thread(String... participants) {
        return MaximegalonDocument.builder()
                .id("t1")
                .tenantId("acme")
                .originatorUserId("alice")
                .assignedToUserId("bob")
                .type(MaximegalonType.APPROVAL)
                .requiresAction(true)
                .status(MaximegalonStatus.PENDING)
                .participants(new ArrayList<>(List.of(participants)))
                .build();
    }

    private static HttpServletRequest request(String username) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(AccessFilterBase.ATTR_USERNAME)).thenReturn(username);
        return request;
    }
}
