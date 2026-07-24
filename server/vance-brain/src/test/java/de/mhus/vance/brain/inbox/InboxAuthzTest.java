package de.mhus.vance.brain.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamService;
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
        assertThat(authz.isAuthorized("acme", "alice", "alice")).isTrue();
    }

    @Test
    void isAuthorized_nullAssignee_isFalse() {
        // An unassigned item is never freely accessible.
        assertThat(authz.isAuthorized("acme", "alice", null)).isFalse();
        org.mockito.Mockito.verifyNoInteractions(teamService);
    }

    @Test
    void isAuthorized_sharedTeamAssignee_isTrue() {
        when(teamService.byMember("acme", "alice")).thenReturn(List.of(team("alice", "bob")));

        assertThat(authz.isAuthorized("acme", "alice", "bob")).isTrue();
    }

    @Test
    void isAuthorized_foreignAssigneeNoSharedTeam_isFalse() {
        when(teamService.byMember("acme", "alice")).thenReturn(List.of(team("alice")));

        assertThat(authz.isAuthorized("acme", "alice", "bob")).isFalse();
    }
}
