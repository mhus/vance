package de.mhus.vance.foot.command;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.ProjectListRequest;
import de.mhus.vance.api.ws.ProjectListResponse;
import de.mhus.vance.api.ws.ProjectSummary;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * {@code /project-list [projectGroupId]} — lists projects in the current
 * tenant, optionally filtered by project group.
 */
@Component
public class ProjectListCommand implements SlashCommand {

    private final ConnectionService connection;
    private final ChatTerminal terminal;
    private final SuggestionCache suggestionCache;

    public ProjectListCommand(
            ConnectionService connection,
            ChatTerminal terminal,
            SuggestionCache suggestionCache) {
        this.connection = connection;
        this.terminal = terminal;
        this.suggestionCache = suggestionCache;
    }

    @Override
    public String name() {
        return "project-list";
    }

    @Override
    public String description() {
        return "List projects in the current tenant. Optional: [projectGroupId].";
    }

    @Override
    public List<ArgSpec> argSpec() {
        return List.of(ArgSpec.of("projectGroupId", ArgKind.PROJECT_GROUP));
    }

    @Override
    public void execute(List<String> args) throws Exception {
        if (args.size() > 1) {
            terminal.error("Usage: /project-list [projectGroupId]");
            return;
        }
        String groupId = args.isEmpty() ? null : args.get(0);
        ProjectListResponse response = connection.request(
                MessageType.PROJECT_LIST,
                ProjectListRequest.builder().projectGroupId(groupId).build(),
                ProjectListResponse.class,
                Duration.ofSeconds(10));

        if (response.getProjects() != null) {
            suggestionCache.rememberProjects(response.getProjects().stream()
                    .map(ProjectSummary::getName)
                    .filter(s -> s != null && !s.isBlank())
                    .toList());
        }
        if (response.getProjects() == null || response.getProjects().isEmpty()) {
            terminal.info("No projects.");
            return;
        }
        terminal.info(String.format("%-24s %-24s %-10s %s",
                "NAME", "GROUP", "ENABLED", "TITLE"));
        for (ProjectSummary p : response.getProjects()) {
            terminal.info(String.format("%-24s %-24s %-10s %s",
                    truncate(p.getName(), 24),
                    truncate(Objects.toString(p.getProjectGroupId(), ""), 24),
                    p.isEnabled() ? "yes" : "no",
                    Objects.toString(p.getTitle(), "")));
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
