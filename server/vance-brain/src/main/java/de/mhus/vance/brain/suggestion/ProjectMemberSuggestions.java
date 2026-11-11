package de.mhus.vance.brain.suggestion;

import de.mhus.vance.api.form.FormSuggestionDto;
import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.shared.role.RoleAssignmentDocument;
import de.mhus.vance.shared.role.RoleAssignmentService;
import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@code project-members}: the people working in the form's project — users
 * holding a project role directly, plus the members of every team that holds
 * one. The value is the login name, which is what an assignee field stores.
 *
 * <p>Needs a project; the controller has already checked READ on it. Only
 * active persons are suggested — service accounts and disabled users are
 * nobody's assignee.
 */
@Component
public class ProjectMemberSuggestions implements SuggestionSource {

    public static final String NAME = "project-members";

    private final RoleAssignmentService assignments;
    private final TeamService teamService;
    private final UserService userService;

    public ProjectMemberSuggestions(
            RoleAssignmentService assignments, TeamService teamService, UserService userService) {
        this.assignments = assignments;
        this.teamService = teamService;
        this.userService = userService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<FormSuggestionDto> suggest(SuggestionRequest request) {
        String project = request.projectId();
        if (project == null) return List.of();
        String needle = request.query().toLowerCase(Locale.ROOT);
        List<FormSuggestionDto> out = new ArrayList<>();
        for (String login : memberLogins(request.tenantId(), project)) {
            if (out.size() >= request.limit()) break;
            UserDocument user =
                    userService.findByTenantAndName(request.tenantId(), login).orElse(null);
            if (user == null || !isAssignable(user)) continue;
            String title = user.getTitle();
            boolean matches = login.toLowerCase(Locale.ROOT).contains(needle)
                    || (title != null && title.toLowerCase(Locale.ROOT).contains(needle));
            if (!matches) continue;
            out.add(new FormSuggestionDto(
                    title == null || title.isBlank() ? login : title,
                    title == null || title.isBlank() ? null : login,
                    List.of(login),
                    "person",
                    null));
        }
        return out;
    }

    private Set<String> memberLogins(String tenantId, String project) {
        Set<String> logins = new LinkedHashSet<>();
        for (RoleAssignmentDocument a : assignments.forScope(tenantId, RoleScopeType.PROJECT, project)) {
            if (a.getSubjectType() == RoleSubjectType.USER) {
                logins.add(a.getSubjectId());
            } else {
                TeamDocument team = teamService
                        .findByTenantAndName(tenantId, a.getSubjectId())
                        .orElse(null);
                if (team != null && team.isEnabled()) logins.addAll(team.getMembers());
            }
        }
        return logins;
    }

    private static boolean isAssignable(UserDocument user) {
        return !user.isServiceAccount() && user.getStatus() == UserStatus.ACTIVE;
    }
}
