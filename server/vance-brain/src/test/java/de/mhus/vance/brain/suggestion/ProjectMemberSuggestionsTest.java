package de.mhus.vance.brain.suggestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.form.FormSuggestionDto;
import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.role.RoleAssignmentDocument;
import de.mhus.vance.shared.role.RoleAssignmentService;
import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProjectMemberSuggestionsTest {

    private final RoleAssignmentService assignments = mock(RoleAssignmentService.class);
    private final TeamService teams = mock(TeamService.class);
    private final UserService users = mock(UserService.class);
    private final ProjectMemberSuggestions source = new ProjectMemberSuggestions(assignments, teams, users);

    private static RoleAssignmentDocument member(RoleSubjectType type, String id) {
        RoleAssignmentDocument d = new RoleAssignmentDocument();
        d.setSubjectType(type);
        d.setSubjectId(id);
        return d;
    }

    private void user(String login, String title, boolean serviceAccount) {
        UserDocument u = new UserDocument();
        u.setName(login);
        u.setTitle(title);
        u.setServiceAccount(serviceAccount);
        when(users.findByTenantAndName("acme", login)).thenReturn(Optional.of(u));
    }

    private static SuggestionRequest request(String projectId, String q) {
        return new SuggestionRequest("acme", SecurityContext.SYSTEM, "anna", projectId, q, 8);
    }

    @Test
    void suggestsDirectUsersAndTeamMembersMatchingLoginOrTitle() {
        when(assignments.forScope("acme", RoleScopeType.PROJECT, "p1"))
                .thenReturn(List.of(member(RoleSubjectType.USER, "anna"), member(RoleSubjectType.TEAM, "dev")));
        TeamDocument team = new TeamDocument();
        team.setMembers(List.of("anton", "bob", "anna"));
        when(teams.findByTenantAndName("acme", "dev")).thenReturn(Optional.of(team));
        user("anna", "Anna Schmidt", false);
        user("anton", null, false);
        user("bob", "Bob", false);

        List<FormSuggestionDto> out = source.suggest(request("p1", "an"));

        assertThat(out).extracting(s -> s.values().get(0)).containsExactly("anna", "anton");
        assertThat(out.get(0).label()).isEqualTo("Anna Schmidt");
        assertThat(out.get(1).label()).isEqualTo("anton");
    }

    @Test
    void skipsServiceAccountsAndAnswersNothingWithoutProject() {
        when(assignments.forScope(eq("acme"), eq(RoleScopeType.PROJECT), any()))
                .thenReturn(List.of(member(RoleSubjectType.USER, "_bot")));
        user("_bot", null, true);

        assertThat(source.suggest(request("p1", "bot"))).isEmpty();
        assertThat(source.suggest(request(null, "bot"))).isEmpty();
    }
}
