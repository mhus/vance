package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.RequiresAuth;
import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamService;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

/**
 * CRUD over {@link TeamDocument}. Member lists are passed as a comma-separated
 * string of usernames; {@code update --members} replaces the list wholesale to
 * match {@link TeamService#update}'s contract.
 */
@ShellComponent
@RequiresAuth
@RequiredArgsConstructor
public class TeamCommands {

    private final TeamService teamService;

    @ShellMethod(key = "team list", value = "List teams in a tenant.")
    public String list(@ShellOption(value = {"--tenant", "-T"}) String tenant) {
        List<TeamDocument> all = teamService.all(tenant);
        if (all.isEmpty()) {
            return "(no teams in tenant '" + tenant + "')";
        }
        return Tables.render(
                List.of("NAME", "TITLE", "ENABLED", "MEMBERS"),
                List.<Function<TeamDocument, @Nullable Object>>of(
                        TeamDocument::getName,
                        TeamDocument::getTitle,
                        TeamDocument::isEnabled,
                        team -> team.getMembers().size()),
                all);
    }

    @ShellMethod(key = "team show", value = "Show a team.")
    public String show(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--name", "-n"}) String name) {
        return teamService.findByTenantAndName(tenant, name)
                .map(TeamCommands::renderOne)
                .orElse("Team '" + name + "' not found in tenant '" + tenant + "'.");
    }

    @ShellMethod(key = "team create", value = "Create a team. --members is comma-separated.")
    public String create(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--name", "-n"}) String name,
            @ShellOption(value = {"--title", "-t"}, defaultValue = ShellOption.NULL) @Nullable String title,
            @ShellOption(value = {"--members"}, defaultValue = ShellOption.NULL) @Nullable String members) {
        TeamDocument team = teamService.create(tenant, name, title, parseList(members));
        return "Created:\n" + renderOne(team);
    }

    @ShellMethod(key = "team update", value = "Update mutable fields of a team. --members replaces the list.")
    public String update(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--name", "-n"}) String name,
            @ShellOption(value = {"--title", "-t"}, defaultValue = ShellOption.NULL) @Nullable String title,
            @ShellOption(value = {"--enabled"}, defaultValue = ShellOption.NULL) @Nullable Boolean enabled,
            @ShellOption(value = {"--members"}, defaultValue = ShellOption.NULL,
                    help = "Comma-separated usernames — replaces the list wholesale")
            @Nullable String members) {
        TeamDocument team = teamService.update(tenant, name, title, enabled, parseList(members));
        return "Updated:\n" + renderOne(team);
    }

    @ShellMethod(key = "team delete", value = "Hard-delete a team.")
    public String delete(
            @ShellOption(value = {"--tenant", "-T"}) String tenant,
            @ShellOption(value = {"--name", "-n"}) String name) {
        teamService.delete(tenant, name);
        return "Deleted team '" + name + "' in tenant '" + tenant + "'.";
    }

    private static @Nullable List<String> parseList(@Nullable String csv) {
        if (StringUtils.isBlank(csv)) {
            return null;
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String renderOne(TeamDocument t) {
        return "  tenantId  : " + t.getTenantId() + "\n"
                + "  name      : " + t.getName() + "\n"
                + "  title     : " + (t.getTitle() == null ? "" : t.getTitle()) + "\n"
                + "  enabled   : " + t.isEnabled() + "\n"
                + "  members   : " + t.getMembers() + "\n"
                + "  created   : " + (t.getCreatedAt() == null ? "" : t.getCreatedAt()) + "\n"
                + "  id        : " + (t.getId() == null ? "" : t.getId());
    }
}
