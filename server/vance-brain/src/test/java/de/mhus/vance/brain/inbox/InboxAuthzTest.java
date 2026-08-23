package de.mhus.vance.brain.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Security-critical: {@link InboxAuthz} is the inbox cross-user visibility
 * gate (own item, or share a team with the assignee). A regression here
 * would silently widen cross-user read/tamper access, so pin every branch.
 */
class InboxAuthzTest {

    private final TeamService teamService = mock(TeamService.class);
    private final InboxAuthz authz = new InboxAuthz(teamService);

    private static TeamDocument team(String... members) {
        return TeamDocument.builder().members(List.of(members)).build();
    }

    @Test
    void sharesTeam_sameUser_isTrue_withoutQueryingTeams() {
        assertThat(authz.sharesTeam("acme", "alice", "alice")).isTrue();
        // Self-match short-circuits before any team lookup.
        org.mockito.Mockito.verifyNoInteractions(teamService);
    }

    @Test
    void sharesTeam_sharedTeam_isTrue() {
        when(teamService.byMember("acme", "alice")).thenReturn(List.of(team("alice", "bob")));

        assertThat(authz.sharesTeam("acme", "alice", "bob")).isTrue();
    }

    @Test
    void sharesTeam_noSharedTeam_isFalse() {
        when(teamService.byMember("acme", "alice")).thenReturn(List.of(team("alice", "carol")));

        assertThat(authz.sharesTeam("acme", "alice", "bob")).isFalse();
    }

    @Test
    void sharesTeam_noTeamsAtAll_isFalse() {
        when(teamService.byMember("acme", "alice")).thenReturn(List.of());

        assertThat(authz.sharesTeam("acme", "alice", "bob")).isFalse();
    }

    @Test
    void sharesTeam_scopesTeamLookupToTheGivenTenant() {
        when(teamService.byMember("acme", "alice")).thenReturn(List.of(team("alice", "bob")));

        authz.sharesTeam("acme", "alice", "bob");

        verify(teamService).byMember("acme", "alice");
    }

    @Test
    void isAuthorized_ownItem_isTrue() {
        assertThat(authz.mayDecide("acme", "alice", "alice")).isTrue();
    }

    @Test
    void isAuthorized_nullAssignee_isFalse() {
        // An unassigned item is never freely accessible.
        assertThat(authz.mayDecide("acme", "alice", null)).isFalse();
        org.mockito.Mockito.verifyNoInteractions(teamService);
    }

    @Test
    void isAuthorized_sharedTeamAssignee_isTrue() {
        when(teamService.byMember("acme", "alice")).thenReturn(List.of(team("alice", "bob")));

        assertThat(authz.mayDecide("acme", "alice", "bob")).isTrue();
    }

    @Test
    void isAuthorized_foreignAssigneeNoSharedTeam_isFalse() {
        when(teamService.byMember("acme", "alice")).thenReturn(List.of(team("alice")));

        assertThat(authz.mayDecide("acme", "alice", "bob")).isFalse();
    }

    // ──── maySee: the widening, and what it must not widen ──────────────

    /** A participant reads and contributes — that is what the list is for. */
    @Test
    void maySee_participant_isTrue_evenWithoutTeamOrAssignment() {
        when(teamService.byMember("acme", "cecilia")).thenReturn(List.of());
        MaximegalonDocument doc = MaximegalonDocument.builder()
                .assignedToUserId("bob")
                .participants(new ArrayList<>(List.of("alice", "bob", "cecilia")))
                .build();

        assertThat(authz.maySee("acme", "cecilia", doc)).isTrue();
    }

    /** The whole point of splitting the predicate: seeing is not deciding. */
    @Test
    void mayDecide_isNotWidenedByParticipation() {
        when(teamService.byMember("acme", "cecilia")).thenReturn(List.of());
        MaximegalonDocument doc = MaximegalonDocument.builder()
                .assignedToUserId("bob")
                .participants(new ArrayList<>(List.of("bob", "cecilia")))
                .build();

        assertThat(authz.maySee("acme", "cecilia", doc)).isTrue();
        assertThat(authz.mayDecide("acme", "cecilia", doc.getAssignedToUserId())).isFalse();
    }

    /** A declared team may look on without being listed as a participant. */
    @Test
    void maySee_declaredTeamMember_isTrue() {
        TeamDocument support = new TeamDocument();
        support.setName("support");
        when(teamService.byMember("acme", "dave")).thenReturn(List.of(support));
        MaximegalonDocument doc = MaximegalonDocument.builder()
                .assignedToUserId("bob")
                .teamId("support")
                .participants(new ArrayList<>())
                .build();

        assertThat(authz.maySee("acme", "dave", doc)).isTrue();
    }

    /** Declaring a team narrows nobody who was allowed before it existed. */
    @Test
    void maySee_declaredTeam_doesNotExcludeTheAssignee() {
        when(teamService.byMember("acme", "bob")).thenReturn(List.of());
        MaximegalonDocument doc = MaximegalonDocument.builder()
                .assignedToUserId("bob")
                .teamId("support")
                .participants(new ArrayList<>())
                .build();

        assertThat(authz.maySee("acme", "bob", doc)).isTrue();
    }

    /** With no team declared the historical derived rule still applies. */
    @Test
    void maySee_noTeamNoParticipants_fallsBackToTheDerivedRule() {
        when(teamService.byMember("acme", "alice")).thenReturn(List.of(team("alice", "bob")));
        MaximegalonDocument doc = MaximegalonDocument.builder()
                .assignedToUserId("bob")
                .participants(new ArrayList<>())
                .build();

        assertThat(authz.maySee("acme", "alice", doc)).isTrue();
    }

    @Test
    void maySee_strangerToEverything_isFalse() {
        when(teamService.byMember("acme", "eve")).thenReturn(List.of());
        MaximegalonDocument doc = MaximegalonDocument.builder()
                .assignedToUserId("bob")
                .participants(new ArrayList<>(List.of("alice", "bob")))
                .build();

        assertThat(authz.maySee("acme", "eve", doc)).isFalse();
    }
}
