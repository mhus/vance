package de.mhus.vance.brain.inbox.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.InboxComposeRequest;
import de.mhus.vance.api.inbox.InboxRecipientDto;
import de.mhus.vance.api.inbox.InboxRecipientKind;
import de.mhus.vance.api.inbox.MaximegalonStatus;
import de.mhus.vance.api.inbox.MaximegalonType;
import de.mhus.vance.brain.inbox.InboxAuthz;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.inbox.InboxEffectRegistry;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonRuleException;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserStatus;
import de.mhus.vance.shared.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

/**
 * What the compose path creates, beyond the delivery authorization the
 * {@link InboxControllerDeliveryAuthzTest} family asserts. The invariants here
 * are silent when broken — a message that turns out to be an ask, an item for a
 * name that is nobody, a team send that reaches nobody — they misbehave quietly
 * in a badge rather than failing loudly at the door.
 */
class InboxControllerComposeTest {

    private static final String TENANT = "acme";
    private static final String ME = "wile.coyote";
    private static final String OTHER = "road.runner";

    private MaximegalonService service;
    private UserService userService;
    private TeamService teamService;
    private RequestAuthority authority;
    private InboxController controller;
    private HttpServletRequest httpRequest;

    @BeforeEach
    void setUp() {
        service = mock(MaximegalonService.class);
        userService = mock(UserService.class);
        teamService = mock(TeamService.class);
        authority = mock(RequestAuthority.class);
        when(teamService.byMember(any(), any())).thenReturn(List.of());
        controller = new InboxController(
                service, new InboxEffectRegistry(List.of()), teamService,
                mock(ProjectService.class), authority, new InboxAuthz(teamService),
                mock(de.mhus.vance.shared.document.DocumentService.class),
                userService);

        httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getAttribute(AccessFilterBase.ATTR_USERNAME)).thenReturn(ME);

        // The happy path has a recipient; tests that need it gone override.
        when(userService.findByTenantAndName(TENANT, OTHER))
                .thenReturn(Optional.of(user(OTHER)));
        when(userService.findByTenantAndName(TENANT, ME))
                .thenReturn(Optional.of(user(ME)));
        when(service.create(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static UserDocument user(String name) {
        return UserDocument.builder()
                .name(name)
                .status(UserStatus.ACTIVE)
                .build();
    }

    private static TeamDocument team(String name, String... members) {
        return TeamDocument.builder()
                .tenantId(TENANT)
                .name(name)
                .members(new java.util.ArrayList<>(List.of(members)))
                .build();
    }

    private static InboxComposeRequest composeBody(String recipient) {
        return InboxComposeRequest.builder()
                .assignedToUserId(recipient)
                .title("Lunch?")
                .body("Twelve, at the usual place?")
                .build();
    }

    /**
     * What the service was asked to persist, after asserting how often —
     * the fan-out writes more than one, and the assertions target one of them.
     */
    private MaximegalonDocument created(int expectedCreates) {
        ArgumentCaptor<MaximegalonDocument> captor =
                ArgumentCaptor.forClass(MaximegalonDocument.class);
        verify(service, times(expectedCreates)).create(captor.capture());
        return captor.getAllValues().get(expectedCreates - 1);
    }

    // ──────────────────── single user ────────────────────

    @Test
    void compose_isNeverAnAsk() {
        controller.compose(TENANT, composeBody(OTHER), httpRequest);
        MaximegalonDocument created = created(1);
        assertThat(created.isRequiresAction()).isFalse();
        assertThat(created.getType()).isEqualTo(MaximegalonType.OUTPUT_TEXT);
        assertThat(created.getCriticality()).isEqualTo(Criticality.NORMAL);
        assertThat(created.getStatus()).isEqualTo(MaximegalonStatus.PENDING);
    }

    @Test
    void compose_defaultsTheRecipientToTheCaller() {
        // The self-note is the case the Milliways share path refuses — without
        // this default, the compose dialog would have nothing to fall to.
        controller.compose(TENANT, composeBody(null), httpRequest);
        MaximegalonDocument created = created(1);
        assertThat(created.getAssignedToUserId()).isEqualTo(ME);
        assertThat(created.getOriginatorUserId()).isEqualTo(ME);
    }

    @Test
    void compose_carriesTheOriginTag() {
        // Herkunft distinguishable at a glance: share against discussion
        // against message — same convention the siblings established.
        controller.compose(TENANT, composeBody(OTHER), httpRequest);
        assertThat(created(1).getTags()).containsExactly("message");
    }

    @Test
    void compose_rejectsAnUnknownRecipient() {
        when(userService.findByTenantAndName(TENANT, OTHER))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.compose(TENANT, composeBody(OTHER), httpRequest))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(404));

        // Undeliverable mail: nothing is written for a name that is nobody.
        verify(service, never()).create(any());
    }

    @Test
    void compose_rejectsServiceAccountsAndDisabledUsers() {
        when(userService.findByTenantAndName(TENANT, OTHER)).thenReturn(Optional.of(
                UserDocument.builder().name(OTHER).serviceAccount(true).build()));
        when(userService.findByTenantAndName(TENANT, ME)).thenReturn(Optional.of(
                UserDocument.builder().name(ME).status(UserStatus.DISABLED).build()));

        assertThatThrownBy(() -> controller.compose(TENANT, composeBody(OTHER), httpRequest))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.compose(TENANT, composeBody(ME), httpRequest))
                .isInstanceOf(ResponseStatusException.class);
        verify(service, never()).create(any());
    }

    // ──────────────────── team fan-out ────────────────────

    @Test
    void compose_rejectsUserAndTeamAtTheSameTime() {
        // Two different shapes of send, not additive — the client has to pick.
        InboxComposeRequest both = InboxComposeRequest.builder()
                .assignedToUserId(OTHER).teamName("devs").title("Lunch?").build();

        assertThatThrownBy(() -> controller.compose(TENANT, both, httpRequest))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        verify(service, never()).create(any());
    }

    @Test
    void compose_toTeam_fansOutOneThreadPerDeliverableMember() {
        TeamDocument devs = team("devs", ME, OTHER,
                "r2d2", "gone", "stranger");
        when(teamService.findByTenantAndName(TENANT, "devs")).thenReturn(Optional.of(devs));
        when(userService.findByTenantAndName(TENANT, "r2d2")).thenReturn(Optional.of(
                UserDocument.builder().name("r2d2").serviceAccount(true).build()));
        when(userService.findByTenantAndName(TENANT, "gone")).thenReturn(Optional.of(
                UserDocument.builder().name("gone").status(UserStatus.DISABLED).build()));
        when(userService.findByTenantAndName(TENANT, "stranger"))
                .thenReturn(Optional.of(user("stranger")));
        // Everything but the stranger passes — the stranger is the case where
        // the delivery gate refuses mid-fan-out: skip, don't fail. Who
        // actually got it is the answer, not an assumption.
        when(authority.check(eq(httpRequest),
                argThat((Resource r) -> !(r instanceof Resource.InboxItem i)
                        || !"stranger".equals(i.assignedToUserId())),
                eq(Action.WRITE)))
                .thenReturn(true);

        InboxComposeRequest body = InboxComposeRequest.builder()
                .teamName("devs").title("Lunch?").build();
        List<String> delivered = controller.compose(TENANT, body, httpRequest)
                .getBody().getDeliveredTo();

        // The sender wrote it; r2d2 is not a desk; gone is gone; stranger
        // is gated. One thread for the one member left.
        assertThat(delivered).containsExactly(OTHER);
        MaximegalonDocument created = created(1);
        assertThat(created.getAssignedToUserId()).isEqualTo(OTHER);
        assertThat(created.getOriginatorUserId()).isEqualTo(ME);
        assertThat(created.getTags()).containsExactly("message");
    }

    @Test
    void compose_toTeam_isRefusedWhenTheCallerIsNotAMember() {
        // The same line the team-inbox view draws — 404 hides existence of a
        // team the caller is not in, rather than explaining it.
        when(teamService.findByTenantAndName(TENANT, "devs"))
                .thenReturn(Optional.of(team("devs", OTHER)));

        InboxComposeRequest body = InboxComposeRequest.builder()
                .teamName("devs").title("Lunch?").build();

        assertThatThrownBy(() -> controller.compose(TENANT, body, httpRequest))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        verify(service, never()).create(any());
    }

    @Test
    void compose_toTeam_isRefusedWhenNobodyIsDeliverable() {
        // A single-member team: the sender is the only member, and they wrote
        // it. 201 with an empty list would read as "done".
        when(teamService.findByTenantAndName(TENANT, "devs"))
                .thenReturn(Optional.of(team("devs", ME)));

        InboxComposeRequest body = InboxComposeRequest.builder()
                .teamName("devs").title("Lunch?").build();

        assertThatThrownBy(() -> controller.compose(TENANT, body, httpRequest))
                .isInstanceOfSatisfying(MaximegalonRuleException.class,
                        e -> assertThat(e.getReason()).isEqualTo(
                                MaximegalonRuleException.NO_DELIVERABLE_MEMBERS));
        verify(service, never()).create(any());
    }

    // ──────────────────── recipient search ────────────────────

    @Test
    void recipients_listsOnlyDeliverableActiveHumans() {
        when(userService.all(TENANT)).thenReturn(List.of(
                user(ME),
                user(OTHER),
                UserDocument.builder().name("r2d2").serviceAccount(true).build(),
                UserDocument.builder().name("gone").status(UserStatus.DISABLED).build(),
                UserDocument.builder().name("stranger").status(UserStatus.ACTIVE).build()));
        // Everything but the stranger passes — the stranger is the case where
        // the picker must not offer what the send would then refuse.
        when(authority.check(any(HttpServletRequest.class),
                argThat((Resource r) -> !(r instanceof Resource.InboxItem i)
                        || !"stranger".equals(i.assignedToUserId())),
                any(Action.class)))
                .thenReturn(true);

        // Sorted by display label, case-insensitive: road.runner < wile.coyote.
        assertThat(controller.recipients(TENANT, null, null, httpRequest).getRecipients())
                .extracting("name")
                .containsExactly(OTHER, ME);
    }

    @Test
    void recipients_labelsTheTitleWithTheName() {
        // Two colleagues called "Mara" have to be told apart in a picker —
        // same formatting the Milliways share handler uses for its form.
        when(userService.all(TENANT)).thenReturn(List.of(UserDocument.builder()
                .name(ME).title("Wile E. Coyote").status(UserStatus.ACTIVE).build()));
        when(authority.check(any(HttpServletRequest.class), any(Resource.class), any(Action.class)))
                .thenReturn(true);

        assertThat(controller.recipients(TENANT, null, null, httpRequest).getRecipients())
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.getName()).isEqualTo(ME);
                    assertThat(r.getDisplayName()).isEqualTo("Wile E. Coyote (wile.coyote)");
                });
    }

    @Test
    void recipients_includesTheCallersTeamsFirst() {
        // Teams come first — the "many desks" pick must not be buried under
        // users — and only the caller's: the team-inbox view draws the same
        // line, membership is what makes the fan-out meaningful.
        when(teamService.byMember(TENANT, ME)).thenReturn(List.of(
                team("devs", ME, OTHER),
                TeamDocument.builder().tenantId(TENANT).name("qa")
                        .title("Quality Assurance").members(new java.util.ArrayList<>())
                        .build()));
        when(userService.all(TENANT)).thenReturn(List.of(user(OTHER)));
        when(authority.check(any(HttpServletRequest.class), any(Resource.class), any(Action.class)))
                .thenReturn(true);

        List<InboxRecipientDto> out =
                controller.recipients(TENANT, null, null, httpRequest).getRecipients();
        assertThat(out).extracting("name")
                .containsExactly("devs", "qa", OTHER);
        assertThat(out.get(0).getKind()).isEqualTo(InboxRecipientKind.TEAM);
        assertThat(out.get(0).getDisplayName()).isEqualTo("devs");
        assertThat(out.get(1).getDisplayName()).isEqualTo("Quality Assurance (qa)");
        assertThat(out.get(2).getKind()).isEqualTo(InboxRecipientKind.USER);
    }

    @Test
    void recipients_matchesNameOrTitleCaseInsensitively() {
        when(userService.all(TENANT)).thenReturn(List.of(
                user("mara.jade"),
                UserDocument.builder().name("luke").title("Luke Skywalker")
                        .status(UserStatus.ACTIVE).build(),
                user("han")));
        when(authority.check(any(HttpServletRequest.class), any(Resource.class), any(Action.class)))
                .thenReturn(true);

        // "luke" hits by name, "SKYW" by title, "boba" nothing.
        assertThat(controller.recipients(TENANT, "luke", null, httpRequest).getRecipients())
                .extracting("name").containsExactly("luke");
        assertThat(controller.recipients(TENANT, "SKYW", null, httpRequest).getRecipients())
                .extracting("name").containsExactly("luke");
        assertThat(controller.recipients(TENANT, "boba", null, httpRequest).getRecipients())
                .isEmpty();
    }

    @Test
    void recipients_capsAtTheLimitAndReportsTruncation() {
        when(userService.all(TENANT)).thenReturn(List.of(
                user("a"), user("b"), user("c")));
        when(authority.check(any(HttpServletRequest.class), any(Resource.class), any(Action.class)))
                .thenReturn(true);

        var page = controller.recipients(TENANT, null, 2, httpRequest);
        assertThat(page.getRecipients()).extracting("name").containsExactly("a", "b");
        assertThat(page.isTruncated()).isTrue();

        // Exactly at the limit: full, but nothing hidden.
        var full = controller.recipients(TENANT, null, 3, httpRequest);
        assertThat(full.getRecipients()).extracting("name").containsExactly("a", "b", "c");
        assertThat(full.isTruncated()).isFalse();

        // A limit above the ceiling is clamped down, not honoured.
        assertThat(controller.recipients(TENANT, null, 500, httpRequest).getRecipients())
                .hasSize(3);
    }
}
