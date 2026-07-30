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
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/**
 * CRUD over {@link TeamDocument}. Member lists are passed as a comma-separated
 * string of usernames; {@code update --members} replaces the list wholesale to
 * match {@link TeamService#update}'s contract.
 */
@Component
@RequiresAuth
@RequiredArgsConstructor
public class TeamCommands {

    private final TeamService teamService;

    @Command(name = {"team", "list"}, description = "List teams in a tenant.")
    public String list(@Option(longName = "tenant", shortName = 'T', required = true) String tenant) {
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

    @Command(name = {"team", "show"}, description = "Show a team.")
    public String show(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "name", shortName = 'n', required = true) String name) {
        return teamService.findByTenantAndName(tenant, name)
                .map(TeamCommands::renderOne)
                .orElse("Team '" + name + "' not found in tenant '" + tenant + "'.");
    }

    @Command(name = {"team", "create"}, description = "Create a team. --members is comma-separated.")
    public String create(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "name", shortName = 'n', required = true) String name,
            @Option(longName = "title", shortName = 't') @Nullable String title,
            @Option(longName = "members") @Nullable String members) {
        TeamDocument team = teamService.create(tenant, name, title, parseList(members));
        return "Created:\n" + renderOne(team);
    }

    @Command(name = {"team", "update"}, description = "Update mutable fields of a team. --members replaces the list.")
    public String update(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "name", shortName = 'n', required = true) String name,
            @Option(longName = "title", shortName = 't') @Nullable String title,
            @Option(longName = "enabled") @Nullable Boolean enabled,
            @Option(longName = "members",
                    description = "Comma-separated usernames — replaces the list wholesale")
            @Nullable String members) {
        TeamDocument team = teamService.update(tenant, name, title, enabled, parseList(members));
        return "Updated:\n" + renderOne(team);
    }

    @Command(name = {"team", "delete"}, description = "Hard-delete a team.")
    public String delete(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "name", shortName = 'n', required = true) String name) {
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
